package dk.cbavf.studio.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate

/**
 * The Studio's own gates, offered on its back as rack outputs: two switches you set by hand and a clock whose gate is on for the
 * first part of each period (math parameter gate.duty, half by default). [run] keeps the router's numbers up to date every frame.
 */
class GateBank {
    var a by mutableStateOf(false)
    var b by mutableStateOf(false)
    /** Clock rate, times a second. */
    var clockHz by mutableFloatStateOf(1f)

    /** The clock's gate [tSec] seconds after it started. */
    fun clockOn(tSec: Double): Boolean = (tSec * clockHz).mod(1.0) < dk.cbavf.studio.math.MathParams.get("gate.duty")

    suspend fun run(router: Router, gate: RunGate = Foreground.gate) {
        val t0 = withFrameMillis { it }
        while (true) {
            // Off screen the clock does not tick (frames stop too, but this does not depend on it).
            gate.awaitStarted()
            val now = withFrameMillis { it }
            router.publishLevel("gates.a", if (a) 1f else 0f)
            router.publishLevel("gates.b", if (b) 1f else 0f)
            router.publishLevel("gates.clock", if (clockOn((now - t0) / 1000.0)) 1f else 0f)
        }
    }
}
