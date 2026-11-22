package com.prism.music.ytmd

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.connect.Renderer
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toSong
import com.prism.music.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Playing on YouTube Music Desktop, the phone's side, as casting to a Prism TV is ([com.prism.music.connect.TvCast]):
 * the phone's own player carries on, muted, so the queue, autoplay, lyrics, the notification and play counts all go on
 * as ever, and it leads what plays. Each song it moves to is sent to the PC by its video id (`changeVideo`: YTMD plays
 * it from YouTube Music itself, nothing is streamed from the phone); play, pause, seeks and the volume go across.
 *
 * The PC leads where: its realtime `state-update`s move the phone's playhead with it, so lyrics stay with what's heard,
 * and pausing, playing or picking another song on the PC comes back here. A song YouTube Music doesn't have (a file on
 * the phone, a podcast by RSS) plays on the phone meanwhile, the PC paused. Runs on the main thread.
 */
class YtmdCast(
    private val c: AppContainer,
    val renderer: Renderer,
    private val api: YtmdApi,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
    /** The PC went (the connection was lost, or it stopped taking the token): why. */
    private val lost: (String) -> Unit,
    /** Its volume, or whether the phone plays the song itself, changed. */
    private val changed: () -> Unit,
) {
    @Volatile var volume: Int? = null
        private set
    /** The song playing can't go to the PC: the phone plays it itself, the PC waits paused. */
    var local = false
        private set

    private val wire = Mutex()
    private var closed = false
    private var feed: Job? = null
    private var realtime: YtmdRealtime? = null

    /** Read on the wire's thread too (waiting for a song sent to start). */
    @Volatile private var last: YtmdState? = null
    private var lastAt = 0L
    /** The song last sent, and when: until the PC says it has it, what it reports is about the one before. */
    private var sentId: String? = null
    private var sentAt = 0L
    private var lastCommandAt = 0L
    private var lastSeekAt = 0L
    private var lastFollowAt = 0L
    private var volumeSetAt = 0L
    /** A place the phone went to itself, to stay with the PC: not sent back. */
    private var followTarget: Long? = null
    /** A play / pause the phone did because the PC did it: not sent back. */
    private var fromPc: Boolean? = null
    /** Another song on the PC than here, since when (it's only taken up when it stays). */
    private var foreignId: String? = null
    private var foreignSince = 0L

    fun start() {
        feed = scope.launch(Dispatchers.IO) { listen() }
    }

    /** Playing on the PC is over: it pauses (the music comes back to the phone, or stops). */
    fun end() {
        if (closed) return
        closed = true
        feed?.cancel()
        realtime?.close()
        scope.launch(Dispatchers.IO) { runCatching { withTimeoutOrNull(4_000) { api.pause() } } }
    }

    /** Where the PC was last heard to be in the song playing here, as of now (null: not known lately). */
    fun pcPlace(): Long? {
        val s = last ?: return null
        val p = player() ?: return null
        if (s.videoId != p.currentMediaItem?.mediaId || SystemClock.elapsedRealtime() - lastAt > 5_000) return null
        return s.progressMs + if (s.playing) SystemClock.elapsedRealtime() - lastAt else 0
    }

    fun setVolume(level: Int) {
        val v = level.coerceIn(0, 100)
        volume = v
        volumeSetAt = SystemClock.elapsedRealtime()
        send("volume") { api.setVolume(v) }
    }

    // ------------------------------------------------------------ What the PC says

    /** The realtime feed, and while it can't be had, the state asked for every so often (the REST API is rate limited). */
    private suspend fun listen() {
        var failingSince = 0L
        while (!closed && kotlinx.coroutines.currentCoroutineContext().isActive) {
            val token = api.token ?: return
            val rt = YtmdRealtime(api.host, api.port, token)
            realtime = rt
            val ok = runCatching { rt.run(onConnected = { failingSince = 0L }) { st -> failingSince = 0L; scope.launch { onState(st) } } }
            if (closed) return
            val e = ok.exceptionOrNull()
            if (e is YtmdException && (e.code == 401 || e.code == 403)) { scope.launch { lost(tr(R.string.ytmd_not_allowed, renderer.name)) }; return }
            Log.w(TAG, "realtime feed from ${renderer.name} ended: ${e?.message}")
            // Asked instead for a while, then the feed's tried again.
            val until = SystemClock.elapsedRealtime() + POLL_SPELL_MS
            while (!closed && SystemClock.elapsedRealtime() < until) {
                val r = runCatching { api.state() }
                r.onSuccess { st -> failingSince = 0L; scope.launch { onState(st) } }
                r.onFailure { err ->
                    if (err is YtmdException && (err.code == 401 || err.code == 403)) { scope.launch { lost(tr(R.string.ytmd_not_allowed, renderer.name)) }; return }
                    if (failingSince == 0L) failingSince = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - failingSince > GIVE_UP_MS) { scope.launch { lost(tr(R.string.lost_the_connection_to, renderer.name)) }; return }
                }
                delay(if ((r.exceptionOrNull() as? YtmdException)?.code == 429) 5_000 else POLL_MS)
            }
        }
    }

    private fun onState(st: YtmdState) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        last = st
        lastAt = now
        // Its volume, unless it's just been set here (a report from before then would undo it while it's dragged).
        if (st.volume != null && st.volume != volume && now - volumeSetAt > 2_000) { volume = st.volume; changed() }
        if (local || st.adPlaying) return
        val p = player() ?: return
        val here = p.currentMediaItem?.mediaId ?: return
        if (st.videoId == null) return
        if (st.videoId != here) { onForeign(p, st, now); return }
        foreignId = null
        if (sentId == here && now - sentAt < SETTLE_MS && st.buffering) return
        // Played or paused on the PC (its own buttons, a media key there): the phone does the same.
        if (now - lastCommandAt > GRACE_MS) {
            if (st.paused && p.playWhenReady) { fromPc = false; c.player.setPlaying(false); return }
            if (st.playing && !p.playWhenReady) { fromPc = true; c.player.setPlaying(true); return }
        }
        // The phone's playhead follows the PC's (lyrics stay with what's heard).
        if (!p.playWhenReady || !st.playing || p.playbackState != Player.STATE_READY) return
        if (now - lastSeekAt < 2_500 || now - lastFollowAt < 3_000 || now - lastCommandAt < 1_500) return
        val target = st.progressMs
        if (abs(p.currentPosition - target) < FOLLOW_SLACK_MS) return
        lastFollowAt = now
        followTarget = target
        p.seekTo(target.coerceAtLeast(0))
    }

    /**
     * The PC plays another song than the phone. Just after one was sent (it hasn't changed yet), or at a song's end
     * (the PC went on by itself a moment before the phone does): nothing. Otherwise, picked on the PC: the phone goes to
     * it too, in its queue when it's the next or last song, or as a song of its own.
     */
    private fun onForeign(p: ExoPlayer, st: YtmdState, now: Long) {
        val id = st.videoId ?: return
        if (now - sentAt < SETTLE_MS || now - lastCommandAt < GRACE_MS) { foreignId = null; return }
        val left = if (p.duration == C.TIME_UNSET) Long.MAX_VALUE else p.duration - p.currentPosition
        if (left < 6_000) return
        if (foreignId != id) { foreignId = id; foreignSince = now; return }
        if (now - foreignSince < 2_000) return
        foreignId = null
        lastCommandAt = now
        // What's sent back would be the same song: it's playing there already.
        sentId = id
        sentAt = now
        val next = p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        val prev = p.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        when (id) {
            next?.let { p.getMediaItemAt(it).mediaId } -> p.seekToDefaultPosition(next)
            prev?.let { p.getMediaItemAt(it).mediaId } -> p.seekToDefaultPosition(prev)
            else -> {
                val song = Song(
                    id = id,
                    title = st.title ?: return,
                    artists = listOfNotNull(st.author?.let { ArtistRef(it) }),
                    durationSec = (st.durationMs / 1000).toInt(),
                    thumbnail = st.thumbnail,
                    isLive = st.isLive,
                )
                c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, tr(R.string.from, renderer.name)))
                if (st.progressMs > 1_500) c.player.afterPending { followTarget = st.progressMs; c.player.seekTo(st.progressMs) }
            }
        }
        Log.i(TAG, "${renderer.name} plays $id: the phone goes with it")
    }

    // ------------------------------------------------------------ The phone's player changing

    /** The song playing here goes to the PC ([startMs]: from there, mid-song), or plays here when the PC can't have it. */
    fun sendCurrent(startMs: Long = 0) {
        val p = player() ?: return
        val song = p.currentMediaItem?.toSong() ?: return
        val canGo = !song.isLocal && !song.isRss
        if (local == canGo) { local = !canGo; changed() }
        lastCommandAt = SystemClock.elapsedRealtime()
        if (!canGo) { sentId = null; send("pause") { api.pause() }; return }
        if (sentId == song.id && SystemClock.elapsedRealtime() - sentAt < SETTLE_MS && startMs <= 1_500) return
        sentId = song.id
        sentAt = SystemClock.elapsedRealtime()
        val play = p.playWhenReady
        send("changeVideo") {
            api.changeVideo(song.id)
            // It starts playing from the top: once it has the song, it goes where the phone is, and pauses if the phone is.
            if (startMs > 1_500 || !play) {
                withTimeoutOrNull(6_000) { while (last?.videoId != song.id) delay(150) }
                if (startMs > 1_500) api.seek(startMs)
                if (!play) api.pause()
            }
        }
    }

    fun onMediaItemTransition(reason: Int) {
        val p = player() ?: return
        val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) sentId = null
        sendCurrent(if (!natural) p.currentPosition else 0)
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        val echo = fromPc
        fromPc = null
        if (echo == playWhenReady || local) return
        lastCommandAt = SystemClock.elapsedRealtime()
        val p = player()
        // The PC hasn't the song playing here (it was a file, or the first send failed): it's sent now.
        if (playWhenReady && p?.currentMediaItem?.mediaId != last?.videoId && p?.currentMediaItem?.mediaId != sentId) { sendCurrent(p?.currentPosition ?: 0); return }
        send(if (playWhenReady) "play" else "pause") { if (playWhenReady) api.play() else api.pause() }
    }

    fun onSeek(sameSong: Boolean, positionMs: Long) {
        if (!sameSong || local) return
        val target = followTarget
        followTarget = null
        // The phone going to where the PC is: nothing to send back.
        if (target != null && abs(positionMs - target) < 400) return
        lastSeekAt = SystemClock.elapsedRealtime()
        send("seek") { api.seek(positionMs) }
    }

    private fun send(what: String, block: suspend () -> Unit) {
        if (closed) return
        scope.launch(Dispatchers.IO) {
            wire.withLock {
                runCatching { block() }.onFailure { e ->
                    Log.w(TAG, "$what on ${renderer.name} failed: ${e.message}")
                    if (e is YtmdException && (e.code == 401 || e.code == 403)) scope.launch { lost(tr(R.string.ytmd_not_allowed, renderer.name)) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PrismYtmd"
        private const val POLL_MS = 1_500L
        private const val POLL_SPELL_MS = 20_000L
        private const val GIVE_UP_MS = 15_000L
        /** After a song's sent, this long for the PC to load it before what it says counts. */
        private const val SETTLE_MS = 5_000L
        /** After a command from here, this long before a play / pause on the PC is taken as its own. */
        private const val GRACE_MS = 3_000L
        private const val FOLLOW_SLACK_MS = 1_500L
    }
}
