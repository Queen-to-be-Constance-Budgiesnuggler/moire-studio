package dk.cbavf.studio.patch

import androidx.compose.runtime.withFrameMillis
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.patch.model.PatchSignals
import dk.cbavf.studio.routing.Router

/**
 * The Patch bay unit's LFO and touch jacks: while a cable listens to one, each frame its level goes out (the route jacks are sent
 * by [PatchEngine] while the patch is live). It idles while nothing listens, and while the studio is off screen.
 */
class PatchSignalBus(private val patch: PatchEngine) {
    suspend fun run(router: Router, gate: RunGate = Foreground.gate) {
        var t0 = -1L
        while (true) {
            val wanted = PatchSignals.listened(router.doc)
            if (!gate.isStarted || wanted.isEmpty()) {
                t0 = -1L
                gate.sleep(250)
                continue
            }
            val now = withFrameMillis { it }
            if (t0 < 0) t0 = now
            PatchSignals.freeLevels(patch.doc, (now - t0) / 1000.0, patch.touchX, patch.touchY, wanted)
                .forEach { (jack, v) -> router.publishLevel(jack, v) }
        }
    }
}
