package dk.cbavf.studio.patch.model

/** What one owner of the sensor hub needs: its sources, and whether it may use the microphone. */
data class SensorWant(val kinds: Set<SourceKind>, val micAllowed: Boolean)

/** What the hub switches on, from what every owner wants. */
object SensorWants {
    /** The hardware to run: every owner's sources together, and none at all while the studio is off screen ([paused]). */
    fun merge(wants: Collection<SensorWant>, paused: Boolean): SensorWant =
        if (paused) SensorWant(emptySet(), false)
        else SensorWant(wants.flatMap { it.kinds }.toSet(), wants.any { it.micAllowed })

    /** True when the microphone thread should run for [merged]. */
    fun micOn(merged: SensorWant): Boolean = SourceKind.Mic in merged.kinds && merged.micAllowed
}
