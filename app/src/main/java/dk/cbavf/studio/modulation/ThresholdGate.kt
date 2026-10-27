package dk.cbavf.studio.modulation

/** What one reading did to a [ThresholdGate]. */
enum class GateEdge { None, Opened, Closed }

/**
 * A gate opened by a value crossing a level, with a band around the level so a noisy reading does not flicker it.
 *
 * Rising (the default): the gate opens when the value reaches the top of the band (level + band / 2) and closes when it
 * drops below the bottom (level − band / 2). Falling: it opens when the value reaches the bottom and closes when it goes
 * above the top. Inside the band the gate keeps the state it had.
 *
 * Plain Kotlin, no Android: the envelope driver feeds it one sensor reading per frame.
 */
class ThresholdGate {
    var open = false
        private set

    /** Takes one reading and says whether the gate opened, closed or stayed as it was. A reading that is not a number changes nothing. */
    fun next(x: Float, level: Float, band: Float, falling: Boolean = false): GateEdge {
        if (x.isNaN()) return GateEdge.None
        val half = band.coerceAtLeast(0f) / 2f
        val top = level + half
        val bottom = level - half
        val next = if (!falling) {
            if (open) x >= bottom else x >= top
        } else {
            if (open) x <= top else x <= bottom
        }
        return set(next)
    }

    /** No reading (the sensors are off): the gate closes. */
    fun off(): GateEdge = set(false)

    private fun set(next: Boolean): GateEdge {
        if (next == open) return GateEdge.None
        open = next
        return if (next) GateEdge.Opened else GateEdge.Closed
    }
}

/** The sensor jacks an envelope can be triggered by: the names after `sensors.` on the rack, each a level 0..1. */
object SensorTriggers {
    val names = listOf("roll", "pitch", "heading", "light", "mic", "shake")

    /** The jack a sensor name reads from. */
    fun jack(name: String) = "sensors.$name"

    /** The name as shown on a button: "light" → "Light". */
    fun label(name: String) = name.replaceFirstChar { it.uppercase() }
}
