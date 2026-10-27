package dk.cbavf.studio.osc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.routing.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.math.abs

/** Which messages the OSC out unit sends for its four jacks: A to D go out as /studio/a to /studio/d, each a value from 0 to 1. */
object OscOutPlan {
    val CHANNELS = listOf("a", "b", "c", "d")

    /** Smaller changes than this are not sent, so a steady jack does not spam the network. */
    const val MIN_CHANGE = 0.001f

    /**
     * The messages to send now for [values] (one per channel, A to D), given what was [sent] last. [sent] is updated for every
     * message returned. A channel that has never been sent is always sent, whatever its value.
     */
    fun changes(values: FloatArray, sent: FloatArray): List<OscMessage> {
        val out = ArrayList<OscMessage>(0)
        for (i in CHANNELS.indices) {
            val v = values[i].coerceIn(0f, 1f)
            if (sent[i].isNaN() || abs(v - sent[i]) >= MIN_CHANGE) {
                out += OscMessage("/studio/${CHANNELS[i]}", listOf(v))
                sent[i] = v
            }
        }
        return out
    }
}

/**
 * The OSC out unit's bus: while it is on, it reads the four jacks from the router and sends each change to the host and port in
 * the profile. The socket is opened only when on, and closed when it is switched off.
 */
class OscOutBus {
    var on by mutableStateOf(false)

    /** What the last send did, for the back panel. */
    var last by mutableStateOf("")

    suspend fun run(router: Router, host: () -> String, port: () -> Int, gate: RunGate = Foreground.gate) {
        val sent = FloatArray(OscOutPlan.CHANNELS.size) { Float.NaN }
        var sender: OscSender? = null
        var senderHost = ""
        try {
            while (true) {
                // Off, or the studio is off screen: the socket is closed and nothing wakes up.
                if (!on || !gate.isStarted) {
                    sender?.close(); sender = null
                    sent.fill(Float.NaN)
                    gate.sleep(300)
                    continue
                }
                val h = host().trim()
                if (h.isEmpty()) {
                    last = "set a host in Config first"
                    on = false
                    continue
                }
                if (sender == null || h != senderHost) {
                    sender?.close()
                    sent.fill(Float.NaN)
                    val addr = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(h) }.getOrNull() }
                    if (addr == null) {
                        last = "cannot find $h"
                        on = false
                        sender = null
                        continue
                    }
                    sender = OscSender(addr, port())
                    senderHost = h
                }
                val s = sender
                val values = FloatArray(OscOutPlan.CHANNELS.size) { router.level("oscout." + OscOutPlan.CHANNELS[it]) }
                val msgs = OscOutPlan.changes(values, sent)
                if (msgs.isNotEmpty()) {
                    val ok = withContext(Dispatchers.IO) { msgs.all { m -> s.send(m.address, m.args) } }
                    last = if (ok) msgs.last().let { "${it.address} ${"%.2f".format(java.util.Locale.US, it.args[0])}" } else "could not send"
                }
                delay(40)
            }
        } finally {
            sender?.close()
        }
    }
}
