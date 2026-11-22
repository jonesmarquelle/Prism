package com.prism.music.ytmd

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The YouTube Music Desktop output against a pretend YTMD: its state, pairing over HTTP, and the realtime feed. */
class YtmdTest {
    private val stateJson = """
        {"player":{"trackState":1,"videoProgress":42.5,"volume":63,"adPlaying":false,
          "queue":{"autoplay":true,"items":[],"automixItems":[],"isGenerating":false,"isInfinite":false,"repeatMode":0,"selectedItemIndex":0}},
         "video":{"author":"Artist","channelId":"UC1","title":"Song","album":"Album","albumId":"MPRE1","likeStatus":1,
          "thumbnails":[{"url":"small","width":60,"height":60},{"url":"big","width":544,"height":544}],
          "durationSeconds":215,"id":"abc123XYZ_0","isLive":false,"videoType":0,"metadataFilled":true},
         "playlistId":"RDAMVM"}
    """.trimIndent()

    @Test fun readsState() {
        val s = YtmdState.parse(stateJson)!!
        assertTrue(s.playing)
        assertEquals(42_500L, s.progressMs)
        assertEquals(63, s.volume)
        assertEquals("abc123XYZ_0", s.videoId)
        assertEquals("Song", s.title)
        assertEquals("Artist", s.author)
        assertEquals(215_000L, s.durationMs)
        assertEquals("big", s.thumbnail)
        assertFalse(s.isLive)
    }

    @Test fun readsStateWithNothingPlaying() {
        val s = YtmdState.parse("""{"player":{"trackState":-1,"videoProgress":0,"volume":50,"adPlaying":false,"queue":null},"video":null,"playlistId":""}""")!!
        assertNull(s.videoId)
        assertFalse(s.playing)
        assertNull(YtmdState.parse("not json"))
    }

    @Test fun addressesAndIds() {
        val r = YtmdDesktops.renderer("Desk", "192.168.1.20", 9863, "Desk", manual = false)
        assertEquals("ytmd:Desk", r.id)
        assertEquals("192.168.1.20", YtmdDesktops.hostOf(r))
        assertEquals(9863, YtmdDesktops.portOf(r))
        assertFalse(YtmdDesktops.isManual(r))
    }

    /** A Windows PC with Tailscale and idle adapters lists all of them over mDNS: the LAN one goes first. */
    @Test fun ranksAPcsAddressesForThePhone() {
        val pc = listOf("169.254.11.227", "169.254.45.200", "100.66.25.114", "169.254.74.81", "192.168.0.26", "169.254.63.196", "127.0.0.1")
        val ranked = YtmdDesktops.rankHosts(pc, listOf("192.168.0.57" to 24))
        assertEquals("192.168.0.26", ranked.first())
        assertEquals("100.66.25.114", ranked[1])
        assertEquals("127.0.0.1", ranked.last())
        // The phone's own network wins over another private one; without knowing it, 192.168 still beats 10/8.
        assertEquals(listOf("10.0.0.5", "192.168.1.9"), YtmdDesktops.rankHosts(listOf("192.168.1.9", "10.0.0.5"), listOf("10.0.0.40" to 24)))
        assertEquals(listOf("192.168.1.9", "10.0.0.5"), YtmdDesktops.rankHosts(listOf("10.0.0.5", "192.168.1.9"), emptyList()))
        assertEquals(emptyList<String>(), YtmdDesktops.rankHosts(listOf("not an ip"), emptyList()))
    }

    /** YTMD's answer to Android's lookup: a PTR, then (in additionals) its SRV, TXT and an A record per address. */
    @Test fun readsAllAddressesFromAnMdnsAnswer() {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8); out.write(v and 0xFF) }
        fun name(n: String) { n.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
        u16(0); u16(0x8400); u16(0); u16(1); u16(0); u16(4)
        name("_ytmdesktop._tcp.local"); u16(12); u16(1); u16(0); u16(60)
        val inst = "DESKTOP-AADMRAK._ytmdesktop._tcp.local".toByteArray().size + 2
        u16(inst); name("DESKTOP-AADMRAK._ytmdesktop._tcp.local")
        // SRV, name compressed (pointer to the instance name just written, at the PTR's data).
        val instAt = 12 + "_ytmdesktop._tcp.local".length + 2 + 10
        u16(0xC000 or instAt); u16(33); u16(1); u16(0); u16(60)
        val target = ByteArrayOutputStream().apply { "DESKTOP-AADMRAK.local".split('.').forEach { write(it.length); write(it.toByteArray()) }; write(0) }.toByteArray()
        u16(6 + target.size); u16(0); u16(0); u16(9863); out.write(target)
        val targetAt = out.size() - target.size
        for (ip in listOf("169.254.11.227", "100.66.25.114", "192.168.0.26")) {
            u16(0xC000 or targetAt); u16(1); u16(1); u16(0); u16(60); u16(4)
            ip.split('.').forEach { out.write(it.toInt()) }
        }
        val packet = out.toByteArray()
        assertEquals(listOf("169.254.11.227", "100.66.25.114", "192.168.0.26"), MdnsAddresses.addressesFor(packet, "DESKTOP-AADMRAK.local"))
        // Cut short mid-record: the addresses before it still count.
        assertEquals(listOf("169.254.11.227", "100.66.25.114"), MdnsAddresses.addressesFor(packet.copyOf(packet.size - 3), "DESKTOP-AADMRAK.local"))
        assertEquals(emptyList<String>(), MdnsAddresses.addressesFor(packet, "OTHER-PC.local"))
        // A query (not a response) is nobody's answer.
        assertEquals(emptyList<String>(), MdnsAddresses.addressesFor(MdnsAddresses.query("DESKTOP-AADMRAK.local"), "DESKTOP-AADMRAK.local"))
        assertTrue(YtmdDesktops.isOnNetworks("192.168.0.26", listOf("192.168.0.235" to 24)))
        assertFalse(YtmdDesktops.isOnNetworks("100.66.25.114", listOf("192.168.0.235" to 24)))
    }

    @Test fun pairsAndSendsCommands() = FakeYtmd(stateJson).use { pc ->
        val api = YtmdApi(OkHttpClient(), "127.0.0.1", pc.port)
        runBlocking {
            assertEquals(listOf("v1"), api.metadata())
            val code = api.requestCode()
            assertEquals("1234", code)
            assertEquals("tok", api.requestToken(code))
            assertEquals("abc123XYZ_0", api.state().videoId)
            api.changeVideo("zzz")
            api.seek(61_500)
        }
        val posts = pc.requests.filter { it.startsWith("POST /api/v1/command") }
        assertTrue(posts[0], posts[0].contains("\"command\":\"changeVideo\"") && posts[0].contains("\"videoId\":\"zzz\""))
        assertTrue(posts[1], posts[1].contains("\"command\":\"seekTo\"") && posts[1].contains("61.5"))
        assertTrue(pc.requests.first { it.startsWith("GET /api/v1/state") }.contains("Authorization: tok"))
    }

    @Test fun realtimeFeedHandsOnStates() = FakeYtmd(stateJson).use { pc ->
        val rt = YtmdRealtime("127.0.0.1", pc.port, "tok")
        val got = CopyOnWriteArrayList<YtmdState>()
        val connected = CountDownLatch(1)
        val t = thread { runCatching { rt.run(onConnected = { connected.countDown() }) { got += it } } }
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        pc.awaitStates(2)
        rt.close()
        t.join(5_000)
        assertEquals(2, got.size)
        assertEquals("abc123XYZ_0", got[0].videoId)
        assertTrue(pc.ponged)
        assertEquals("tok", pc.authToken)
    }

    @Test fun realtimeRefusedToken() = FakeYtmd(stateJson, refuse = true).use { pc ->
        val e = runCatching { YtmdRealtime("127.0.0.1", pc.port, "bad").run({}) {} }.exceptionOrNull()
        assertTrue("$e", e is YtmdException && e.code == 403)
    }
}

/** Enough of YTMD's companion server: its REST routes, and Socket.IO over a WebSocket on /socket.io/. */
private class FakeYtmd(private val state: String, private val refuse: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0)
    val port get() = server.localPort
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var ponged = false
    @Volatile var authToken: String? = null
    private val statesSent = CountDownLatch(2)

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { s.use { handle(it) } } }
            }
        }
    }

    fun awaitStates(n: Int) { assertTrue(statesSent.await(5, TimeUnit.SECONDS)); Thread.sleep(200) }

    private fun handle(s: Socket) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val out = s.getOutputStream()
        val head = StringBuilder()
        var length = 0
        var key: String? = null
        while (true) {
            val l = line(input)
            if (l.isEmpty()) break
            head.append(l).append('\n')
            if (l.startsWith("Content-Length:", true)) length = l.substringAfter(':').trim().toInt()
            if (l.startsWith("Sec-WebSocket-Key:", true)) key = l.substringAfter(':').trim()
        }
        val body = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        val req = head.toString() + body
        requests += req
        val path = req.substringBefore('\n').split(' ')[1]
        if (key != null) { websocket(key, input, out); return }
        val answer = when {
            path == "/metadata" -> """{"apiVersions":["v1"]}"""
            path.endsWith("/auth/requestcode") -> """{"code":"1234"}"""
            path.endsWith("/auth/request") -> """{"token":"tok"}"""
            path.endsWith("/state") -> state
            path.endsWith("/command") -> ""
            else -> null
        }
        val status = if (answer == null) "404 Not Found" else if (answer.isEmpty()) "204 No Content" else "200 OK"
        val b = (answer ?: "").toByteArray()
        out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray() + b)
        out.flush()
    }

    private fun websocket(key: String, input: DataInputStream, out: OutputStream) {
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        send(out, """0{"sid":"x","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}""")
        while (true) {
            val msg = read(input) ?: return
            when {
                msg.startsWith("40/api/v1/realtime,") -> {
                    authToken = Regex("\"token\":\"([^\"]*)\"").find(msg)?.groupValues?.get(1)
                    if (refuse) { send(out, """44/api/v1/realtime,{"message":"Unauthorized"}"""); continue }
                    send(out, """40/api/v1/realtime,{"sid":"y"}""")
                    send(out, "2")
                    // A state long enough for the 16-bit length, then another.
                    repeat(2) { send(out, "42/api/v1/realtime,[\"state-update\",$state]"); statesSent.countDown() }
                }
                msg == "3" -> ponged = true
            }
        }
    }

    private fun send(out: OutputStream, text: String) {
        val b = text.toByteArray()
        val h = ByteArrayOutputStream()
        h.write(0x81)
        if (b.size < 126) h.write(b.size) else { h.write(126); h.write(b.size ushr 8); h.write(b.size and 0xFF) }
        synchronized(out) { out.write(h.toByteArray() + b); out.flush() }
    }

    /** A client's (masked) text frame. */
    private fun read(input: DataInputStream): String? = runCatching {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        var len = b1 and 0x7F
        if (len == 126) len = input.readUnsignedShort()
        val mask = ByteArray(4).also(input::readFully)
        assertTrue("client frames are masked", b1 and 0x80 != 0)
        val d = ByteArray(len).also(input::readFully)
        for (i in d.indices) d[i] = (d[i].toInt() xor mask[i and 3].toInt()).toByte()
        if (b0 and 0x0F == 0x8) null else String(d)
    }.getOrNull()

    private fun line(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; b.write(c) }
        return b.toString().removeSuffix("\r")
    }

    override fun close() = server.close()
}
