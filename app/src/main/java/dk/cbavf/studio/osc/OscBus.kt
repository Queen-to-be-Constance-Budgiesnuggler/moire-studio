package dk.cbavf.studio.osc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.routing.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/** Which rack jack an OSC address feeds. */
object OscMap {
    private val jacks = mapOf("a" to "osc.a", "b" to "osc.b", "c" to "osc.c", "d" to "osc.d", "1" to "osc.a", "2" to "osc.b", "3" to "osc.c", "4" to "osc.d")

    /** The jack for the last part of [address] (`/studio/a`, `/a`, `/fader/3`: a to d, or 1 to 4), or null. */
    fun jackFor(address: String): String? = jacks[address.trimEnd('/').substringAfterLast('/').lowercase()]
}

/**
 * Open Sound Control in: while [on], a UDP port is listened to and the first number of each message goes out, kept between 0 and 1, on
 * the "OSC in" unit's jack that the address names. A phone app, a laptop patch or a hardware controller can then drive anything that takes a
 * value. Only messages are read, not bundles. The port is closed while the studio is off screen and opened again when it comes back.
 */
class OscBus {
    var on by mutableStateOf(false)

    /** The last message that arrived, for the back panel. */
    var last by mutableStateOf("")

    suspend fun run(router: Router, gate: RunGate = Foreground.gate) {
        while (true) {
            // Off, or the studio is off screen: no port is held and nothing wakes up.
            if (!on || !gate.isStarted) {
                gate.sleep(300)
                continue
            }
            val socket = runCatching { DatagramSocket(PORT).apply { soTimeout = 400 } }.getOrNull()
            if (socket == null) {
                last = "port $PORT is busy"
                on = false
                continue
            }
            try {
                val buf = ByteArray(1024)
                while (on && gate.isStarted) {
                    val got = withContext(Dispatchers.IO) {
                        val p = DatagramPacket(buf, buf.size)
                        try { socket.receive(p); buf.copyOf(p.length) } catch (e: SocketTimeoutException) { null }
                    } ?: continue
                    val m = OscParser.parse(got) ?: continue
                    val jack = OscMap.jackFor(m.address) ?: continue
                    val v = m.args.firstOrNull() ?: continue
                    router.publishLevel(jack, v.coerceIn(0f, 1f))
                    last = "${m.address} ${"%.2f".format(java.util.Locale.US, v)}"
                }
            } finally {
                socket.close()
            }
        }
    }

    companion object {
        const val PORT = 9000
    }
}
