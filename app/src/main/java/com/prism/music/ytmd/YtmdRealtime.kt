package com.prism.music.ytmd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * YTMD's realtime feed: Socket.IO (Engine.IO v4, WebSocket only) on the `/api/v1/realtime` namespace, which sends a
 * `state-update` on every change, so the PC doesn't have to be asked over and over (its REST API is rate limited).
 *
 * Spoken by hand over a socket of Prism's own, as [com.prism.music.connect.LanHttp] does for HTTP: Android doesn't let
 * OkHttp open a plain `ws://` to the local network, and the protocol is small (text frames carrying `0` open, `40`
 * connect, `42` event, `2`/`3` ping / pong). Blocking: [run] returns when the connection ends.
 */
class YtmdRealtime(private val host: String, private val port: Int, private val token: String) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false
    private val random = SecureRandom()

    /**
     * Connects and hands every `state-update` to [onState] until the connection ends (or [close]). [onConnected] once the
     * namespace took the token. Throws [YtmdException] (403) when it's refused.
     */
    fun run(onConnected: () -> Unit, onState: (YtmdState) -> Unit) {
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 5_000)
            s.tcpNoDelay = true
            // Engine.IO pings every 25 s by default: nothing in twice that is a connection that's gone.
            s.soTimeout = 60_000
            val o = s.getOutputStream()
            out = o
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            handshake(o, input)
            while (!closed) {
                val text = readText(input) ?: continue
                when {
                    text.startsWith("0") -> sendText("40$NAMESPACE," + buildJsonObject { put("token", token) })
                    text == "2" -> sendText("3")
                    text.startsWith("40$NAMESPACE") -> onConnected()
                    text.startsWith("44$NAMESPACE") -> throw YtmdException("YTMD refused the realtime connection: ${text.substringAfter(',')}", 403)
                    text.startsWith("41$NAMESPACE") || text == "1" -> return
                    text.startsWith("42$NAMESPACE,") -> event(text.substringAfter(','))?.let { (name, arg) ->
                        if (name == "state-update" && arg is JsonObject) onState(YtmdState.from(arg))
                    }
                }
            }
        } finally {
            close()
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun event(payload: String): Pair<String, Any?>? = runCatching {
        val a = Json.parseToJsonElement(payload).jsonArray
        (a[0] as JsonPrimitive).content to a.getOrNull(1)
    }.getOrNull()

    // ------------------------------------------------------------ WebSocket (RFC 6455), client side

    private fun handshake(o: OutputStream, input: DataInputStream) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val hostHeader = (if (host.contains(':')) "[$host]" else host) + ":$port"
        val req = "GET $PATH?EIO=4&transport=websocket HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: Prism\r\n\r\n"
        o.write(req.toByteArray(Charsets.ISO_8859_1))
        o.flush()
        val status = line(input)
        if (!status.startsWith("HTTP/1.1 101")) throw YtmdException("YTMD didn't open its realtime feed ($status)")
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1))
        )
        var accepted = false
        while (true) {
            val h = line(input)
            if (h.isEmpty()) break
            if (h.substringBefore(':').trim().equals("Sec-WebSocket-Accept", true) && h.substringAfter(':').trim() == expected) accepted = true
        }
        if (!accepted) throw YtmdException("YTMD's realtime feed answered oddly")
    }

    private fun line(input: DataInputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException("YTMD closed the connection")
            if (c == '\n'.code) break
            b.write(c)
            if (b.size() > 8 * 1024) throw IOException("Header line too long")
        }
        return b.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    /** The next whole text message (null for anything else: control frames are answered here). */
    private fun readText(input: DataInputStream): String? {
        val message = ByteArrayOutputStream()
        var textMessage = false
        while (true) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            if (len < 0 || len > MAX_MESSAGE) throw IOException("Realtime message of $len bytes")
            val mask = if (masked) ByteArray(4).also(input::readFully) else null
            val data = ByteArray(len.toInt()).also(input::readFully)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                0x8 -> throw EOFException("YTMD closed the realtime feed")
                0x9 -> { frame(0xA, data); if (message.size() == 0 && fin) return null }
                0xA -> if (message.size() == 0 && fin) return null
                0x1 -> { textMessage = true; message.write(data) }
                0x2 -> { textMessage = false; message.write(data) }
                0x0 -> message.write(data)
            }
            if (message.size() > MAX_MESSAGE) throw IOException("Realtime message too long")
            if (fin && opcode != 0x9 && opcode != 0xA) return if (textMessage) message.toString(Charsets.UTF_8.name()) else null
        }
    }

    private fun sendText(text: String) = frame(0x1, text.toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun frame(opcode: Int, data: ByteArray) {
        val o = out ?: return
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)
        when {
            data.size < 126 -> head.write(0x80 or data.size)
            data.size < 65536 -> { head.write(0x80 or 126); head.write(data.size ushr 8); head.write(data.size and 0xFF) }
            else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((data.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        head.write(mask)
        val body = ByteArray(data.size) { i -> (data[i].toInt() xor mask[i and 3].toInt()).toByte() }
        o.write(head.toByteArray() + body)
        o.flush()
    }

    companion object {
        const val PATH = "/socket.io/"
        const val NAMESPACE = "/api/v1/realtime"
        private const val MAX_MESSAGE = 4L * 1024 * 1024
    }
}
