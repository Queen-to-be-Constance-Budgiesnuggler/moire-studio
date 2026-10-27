package dk.cbavf.studio.patch

import dk.cbavf.studio.patch.model.SensorWant
import dk.cbavf.studio.patch.model.SensorWants
import dk.cbavf.studio.patch.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorWantsTest {
    private val bus = SensorWant(setOf(SourceKind.Roll, SourceKind.Mic), micAllowed = true)
    private val patch = SensorWant(setOf(SourceKind.Light), micAllowed = false)

    @Test fun everyOwnersSourcesAreSwitchedOnTogether() {
        assertEquals(setOf(SourceKind.Roll, SourceKind.Mic, SourceKind.Light), SensorWants.merge(listOf(bus, patch), paused = false).kinds)
    }

    @Test fun nothingRunsWhilePaused() {
        val merged = SensorWants.merge(listOf(bus, patch), paused = true)
        assertTrue(merged.kinds.isEmpty())
        assertFalse(SensorWants.micOn(merged))
    }

    @Test fun theMicRunsWhenOneOwnerWantsItAndMayUseIt() {
        assertTrue(SensorWants.micOn(SensorWants.merge(listOf(bus, patch), paused = false)))
    }

    @Test fun theMicStaysOffWithoutPermission() {
        val noMic = SensorWant(setOf(SourceKind.Mic), micAllowed = false)
        assertFalse(SensorWants.micOn(SensorWants.merge(listOf(noMic), paused = false)))
    }

    @Test fun theMicStaysOffWhenNoOwnerReadsIt() {
        val allowed = SensorWant(setOf(SourceKind.Roll), micAllowed = true)
        assertFalse(SensorWants.micOn(SensorWants.merge(listOf(allowed), paused = false)))
    }

    @Test fun noOwnersMeansNothingRuns() {
        assertTrue(SensorWants.merge(emptyList(), paused = false).kinds.isEmpty())
    }
}
