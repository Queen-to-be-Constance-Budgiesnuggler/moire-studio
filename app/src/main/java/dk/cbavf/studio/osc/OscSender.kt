package dk.cbavf.studio.osc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends OSC messages to one host and port (OSC out). Holds one UDP socket for its lifetime; [close] it when OSC out is switched off.
 * Nothing is sent until [send] is called, so a studio with OSC out off never opens a socket.
 */
class OscSender(private val host: InetAddress, private val port: Int) : AutoCloseable {
    private val socket by lazy { DatagramSocket() }

    /** Sends one message with float arguments. Returns false when the network refuses (no route, no Wi-Fi). */
    fun send(address: String, args: List<Float>): Boolean = runCatching {
        val bytes = OscEncoder.encode(address, args)
        socket.send(DatagramPacket(bytes, bytes.size, host, port))
        true
    }.getOrDefault(false)

    override fun close() {
        if (socket.isClosed) return
        socket.close()
    }
}
