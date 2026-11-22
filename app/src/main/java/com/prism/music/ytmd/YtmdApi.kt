package com.prism.music.ytmd

import com.prism.music.connect.LanHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The desktop said no: [code] is its HTTP status (401/403 a token it doesn't take, 429 too many requests). */
class YtmdException(message: String, val code: Int = -1) : IOException(message)

/**
 * What YouTube Music Desktop (YTMD, v2.0+) is playing, from its companion server's `GET /state` or a realtime
 * `state-update`. Only what Prism needs; everything is optional, as older versions leave fields out.
 */
data class YtmdState(
    /** -1 unknown, 0 paused, 1 playing, 2 buffering. */
    val trackState: Int,
    /** Where it is in the song, ms. */
    val progressMs: Long,
    /** 0–100. */
    val volume: Int?,
    val adPlaying: Boolean,
    val videoId: String?,
    val title: String?,
    val author: String?,
    val album: String?,
    val durationMs: Long,
    val thumbnail: String?,
    val isLive: Boolean,
) {
    val playing get() = trackState == 1
    val paused get() = trackState == 0
    val buffering get() = trackState == 2

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): YtmdState? = runCatching { from(json.parseToJsonElement(text).jsonObject) }.getOrNull()

        fun from(o: JsonObject): YtmdState {
            val player = (o["player"] as? JsonObject)
            val video = (o["video"] as? JsonObject)
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
            val thumbs = runCatching { video?.get("thumbnails")?.jsonArray }.getOrNull()
            // The biggest one: YTMD lists them smallest first.
            val thumb = thumbs?.mapNotNull { it as? JsonObject }?.maxByOrNull { (it.num("width") ?: 0.0) }?.str("url")
            return YtmdState(
                trackState = (player?.get("trackState") as? JsonPrimitive)?.intOrNull ?: -1,
                progressMs = ((player?.num("videoProgress") ?: 0.0) * 1000).toLong(),
                volume = player?.num("volume")?.toInt()?.coerceIn(0, 100),
                adPlaying = (player?.get("adPlaying") as? JsonPrimitive)?.booleanOrNull ?: false,
                videoId = video?.str("id"),
                title = video?.str("title"),
                author = video?.str("author"),
                album = video?.str("album"),
                durationMs = ((video?.num("durationSeconds") ?: 0.0) * 1000).toLong(),
                thumbnail = thumb,
                isLive = (video?.get("isLive") as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }
}

/**
 * YTMD's companion server API v1 (`http://<pc>:9863/api/v1`): pairing ([requestCode], then [requestToken], which the
 * person approves on the PC), what it plays ([state]) and what it's told to do ([command]). Plain HTTP to the PC goes
 * through [LanHttp], as Android won't let OkHttp send it ("CLEARTEXT … not permitted"), so [host] has to be an address
 * on the local network.
 */
class YtmdApi(http: OkHttpClient, val host: String, val port: Int = DEFAULT_PORT) {
    @Volatile var token: String? = null

    private val client = with(LanHttp) { http.newBuilder().lanFirst() }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Approving on the PC can take its whole 30 seconds. */
    private val slowClient = client.newBuilder().readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    private val base get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"

    private suspend fun call(path: String, body: JsonObject? = null, slow: Boolean = false, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base + path).header("User-Agent", "Prism")
        if (auth) token?.let { b.header("Authorization", it) }
        if (body != null) b.post(body.toString().toRequestBody("application/json".toMediaType()))
        (if (slow) slowClient else client).newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw YtmdException("YTMD ${r.code} on $path${message(text)?.let { ": $it" } ?: ""}", r.code)
            text
        }
    }

    private fun message(text: String): String? =
        runCatching { (Json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.content }.getOrNull()

    /** The API versions it speaks (`["v1"]`); also how Prism tells a YTMD is there at all. No token needed. */
    suspend fun metadata(): List<String> = withContext(Dispatchers.IO) {
        val text = client.newCall(Request.Builder().url("$base/metadata").build()).execute().use { r ->
            if (!r.isSuccessful) throw YtmdException("YTMD ${r.code} on /metadata", r.code)
            r.body?.string().orEmpty()
        }
        runCatching { Json.parseToJsonElement(text).jsonObject["apiVersions"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
    }

    /** Step one of pairing: a code to show here, which YTMD shows on the PC too. */
    suspend fun requestCode(): String {
        val text = call("/api/v1/auth/requestcode", buildJsonObject {
            put("appId", APP_ID); put("appName", APP_NAME); put("appVersion", APP_VERSION)
        }, auth = false)
        return field(text, "code") ?: throw YtmdException("YTMD sent no pairing code")
    }

    /** Step two: waits (up to 30 s) for the person to allow [code] on the PC, then the token. Throws when it's denied. */
    suspend fun requestToken(code: String): String {
        val text = call("/api/v1/auth/request", buildJsonObject { put("appId", APP_ID); put("code", code) }, slow = true, auth = false)
        return field(text, "token")?.also { token = it } ?: throw YtmdException("Not allowed on the PC", 403)
    }

    suspend fun state(): YtmdState = YtmdState.parse(call("/api/v1/state")) ?: throw YtmdException("YTMD's state couldn't be read")

    /** One of the companion server's commands (`playPause`, `seekTo`, `changeVideo`…), with its [data] if it takes any. */
    suspend fun command(name: String, data: JsonElement? = null) {
        call("/api/v1/command", buildJsonObject {
            put("command", name)
            if (data != null && data != JsonNull) put("data", data)
        })
    }

    suspend fun play() = command("play")
    suspend fun pause() = command("pause")
    suspend fun seek(ms: Long) = command("seekTo", JsonPrimitive(ms.coerceAtLeast(0) / 1000.0))
    suspend fun setVolume(v: Int) = command("setVolume", JsonPrimitive(v.coerceIn(0, 100)))
    suspend fun changeVideo(videoId: String) = command("changeVideo", buildJsonObject { put("videoId", videoId); put("playlistId", JsonNull) })

    private fun field(text: String, name: String): String? =
        runCatching { (Json.parseToJsonElement(text).jsonObject[name] as? JsonPrimitive)?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_PORT = 9863
        /** Lowercase letters and digits only, 2–32 (YTMD's rule). A new pairing with the same id replaces the old token. */
        const val APP_ID = "prismmusic"
        const val APP_NAME = "Prism"
        const val APP_VERSION = "1.0.0"
    }
}
