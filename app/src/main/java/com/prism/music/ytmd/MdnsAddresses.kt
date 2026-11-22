package com.prism.music.ytmd

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Every IPv4 address a host gives for its `.local` name, asked over mDNS by Prism itself.
 *
 * Before Android 14, Android's resolver hands an app one of a service's addresses ([android.net.nsd.NsdServiceInfo.getHost]),
 * whichever came first, and a PC lists all of its own: on a PC with a VPN (Tailscale's 100.64/10) or idle adapters
 * (169.254) that one is often one the phone can't reach. YTMD (and Windows itself) answer for the PC's name with all of
 * them, multicast, so they're heard here on the mDNS group. Blocking; the caller holds a multicast lock (discovery does).
 */
object MdnsAddresses {
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353
    private const val TYPE_A = 1

    /** The IPv4 addresses answered for [host] ("DESKTOP-1234", or "DESKTOP-1234.local") within [windowMs]; empty for none. */
    fun lookup(host: String, windowMs: Long = 1_500): List<String> {
        val name = if (host.endsWith(".local", ignoreCase = true)) host else "$host.local"
        val iface = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { i ->
                i.isUp && !i.isLoopback && i.supportsMulticast() && i.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
            }
        }.getOrNull() ?: return emptyList()
        val socket = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                joinGroup(InetSocketAddress(GROUP, PORT), iface)
                networkInterface = iface
                soTimeout = 250
            }
        }.getOrNull() ?: return emptyList()
        val found = LinkedHashSet<String>()
        try {
            val q = query(name)
            val until = System.currentTimeMillis() + windowMs
            var sent = 0
            val buf = ByteArray(9_000)
            while (System.currentTimeMillis() < until) {
                // Twice, as UDP can drop one.
                if (sent < 2) { runCatching { socket.send(DatagramPacket(q, q.size, GROUP, PORT)) }; sent++ }
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                found += addressesFor(buf.copyOf(p.length), name)
            }
        } finally {
            runCatching { socket.leaveGroup(InetSocketAddress(GROUP, PORT), iface) }
            runCatching { socket.close() }
        }
        return found.toList()
    }

    /** An mDNS query for [name]'s A records. */
    fun query(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(4)) // id 0, flags 0 (a standard query)
        out.write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)) // one question
        writeName(out, name)
        out.write(byteArrayOf(0, TYPE_A.toByte(), 0, 1)) // A, IN
        return out.toByteArray()
    }

    /** The A records for [name] in an mDNS response (answers and additionals alike); empty for a query or anything unreadable. */
    fun addressesFor(packet: ByteArray, name: String): List<String> {
        val out = ArrayList<String>()
        if (packet.size < 12 || packet[2].toInt() and 0x80 == 0) return out
        // What's read before a record that can't be (cut short, or odd) is kept.
        runCatching { readAddresses(packet, name, out) }
        return out
    }

    private fun readAddresses(packet: ByteArray, name: String, out: MutableList<String>) {
        fun u16(o: Int) = ((packet[o].toInt() and 0xFF) shl 8) or (packet[o + 1].toInt() and 0xFF)
        val questions = u16(4)
        val records = u16(6) + u16(8) + u16(10)
        var o = 12
        repeat(questions) { o = readName(packet, o).second + 4 }
        repeat(records) {
            val (n, end) = readName(packet, o)
            val type = u16(end)
            val length = u16(end + 8)
            val data = end + 10
            if (type == TYPE_A && length == 4 && n.equals(name, ignoreCase = true)) {
                out += (0 until 4).joinToString(".") { (packet[data + it].toInt() and 0xFF).toString() }
            }
            o = data + length
        }
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    /** A name at [start] (following compression pointers) and where the record goes on after it. */
    private fun readName(p: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var o = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = p[o].toInt() and 0xFF
            when {
                len == 0 -> { if (end < 0) end = o + 1; break }
                len and 0xC0 == 0xC0 -> {
                    if (end < 0) end = o + 2
                    if (++jumps > 16) throw IllegalArgumentException("name loop")
                    o = ((len and 0x3F) shl 8) or (p[o + 1].toInt() and 0xFF)
                }
                else -> { labels += String(p, o + 1, len, Charsets.UTF_8); o += 1 + len }
            }
        }
        return labels.joinToString(".") to end
    }
}
