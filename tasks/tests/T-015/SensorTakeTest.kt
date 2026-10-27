package dk.cbavf.studio.sensors

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorTakeTest {
    private fun take() = SensorTake(hz = 10, kinds = listOf("a", "b"), frames = listOf(listOf(0f, 10f), listOf(1f, 20f), listOf(3f, 20f)))

    @Test fun durationFollowsTheFrameCount() {
        assertEquals(200L, take().durationMs)
        assertEquals(0L, SensorTake(10, listOf("a"), listOf(listOf(1f))).durationMs)
        assertEquals(0L, SensorTake(10, listOf("a"), emptyList()).durationMs)
    }

    @Test fun valuesAreInterpolatedBetweenFrames() {
        val t = take()
        assertEquals(listOf(0f, 10f), t.valueAt(0))
        assertEquals(listOf(0.5f, 15f), t.valueAt(50))
        assertEquals(listOf(1f, 20f), t.valueAt(100))
        assertEquals(listOf(2f, 20f), t.valueAt(150))
    }

    @Test fun beforeTheStartAndPastTheEndHoldTheEnds() {
        val t = take()
        assertEquals(listOf(0f, 10f), t.valueAt(-500))
        assertEquals(listOf(3f, 20f), t.valueAt(200))
        assertEquals(listOf(3f, 20f), t.valueAt(9000))
    }

    @Test fun loopingWrapsTheTime() {
        val t = take()
        assertEquals(t.valueAt(50), t.valueAt(250, loop = true))
        assertEquals(t.valueAt(150), t.valueAt(-50, loop = true))
    }

    @Test fun anEmptyTakeAndASingleFrame() {
        assertEquals(emptyList<Float>(), SensorTake(10, listOf("a"), emptyList()).valueAt(100))
        val one = SensorTake(10, listOf("a"), listOf(listOf(7f)))
        assertEquals(listOf(7f), one.valueAt(500))
        assertEquals(listOf(7f), one.valueAt(500, loop = true))
    }

    @Test fun theRecorderKeepsAFrameOnlyWhenOneIsDue() {
        val r = TakeRecorder(listOf("a"), hz = 10)
        r.add(37, listOf(1f))      // the first call always keeps a frame: frame 0
        r.add(60, listOf(2f))      // frame 1 is due at 100 ms: not yet
        r.add(100, listOf(3f))     // due now
        r.add(150, listOf(4f))     // frame 2 is due at 200 ms: not yet
        r.add(230, listOf(5f))     // due
        val t = r.finish()
        assertEquals(10, t.hz)
        assertEquals(listOf("a"), t.kinds)
        assertEquals(listOf(listOf(1f), listOf(3f), listOf(5f)), t.frames)
    }

    @Test fun theRecorderIgnoresValuesOfTheWrongSize() {
        val r = TakeRecorder(listOf("a", "b"), hz = 10)
        r.add(0, listOf(1f))
        r.add(0, listOf(1f, 2f))
        assertEquals(listOf(listOf(1f, 2f)), r.finish().frames)
    }

    @Test fun aTakeSurvivesJson() {
        val t = take()
        val json = Json.encodeToString(SensorTake.serializer(), t)
        assertEquals(t, Json.decodeFromString(SensorTake.serializer(), json))
    }
}
