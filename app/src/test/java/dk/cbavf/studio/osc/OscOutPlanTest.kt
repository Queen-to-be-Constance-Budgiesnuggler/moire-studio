package dk.cbavf.studio.osc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OscOutPlanTest {
    private fun fresh() = FloatArray(4) { Float.NaN }

    @Test fun theFirstTimeEveryChannelIsSent() {
        val sent = fresh()
        val msgs = OscOutPlan.changes(floatArrayOf(0f, 0.5f, 1f, 0.25f), sent)
        assertEquals(listOf("/studio/a", "/studio/b", "/studio/c", "/studio/d"), msgs.map { it.address })
        assertEquals(listOf(0f, 0.5f, 1f, 0.25f), msgs.map { it.args[0] })
    }

    @Test fun aSteadyJackSendsNothing() {
        val sent = fresh()
        OscOutPlan.changes(floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f), sent)
        assertTrue(OscOutPlan.changes(floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f), sent).isEmpty())
    }

    @Test fun onlyTheChangedChannelIsSent() {
        val sent = fresh()
        OscOutPlan.changes(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), sent)
        val msgs = OscOutPlan.changes(floatArrayOf(0.1f, 0.9f, 0.3f, 0.4f), sent)
        assertEquals(listOf("/studio/b"), msgs.map { it.address })
        assertEquals(0.9f, msgs.single().args[0], 0f)
    }

    @Test fun aChangeSmallerThanTheThresholdIsNotSent() {
        val sent = fresh()
        OscOutPlan.changes(floatArrayOf(0.5f, 0f, 0f, 0f), sent)
        assertTrue(OscOutPlan.changes(floatArrayOf(0.5f + OscOutPlan.MIN_CHANGE / 2, 0f, 0f, 0f), sent).isEmpty())
    }

    @Test fun valuesOutsideZeroToOneAreClampedBeforeTheyAreSent() {
        val sent = fresh()
        val msgs = OscOutPlan.changes(floatArrayOf(-3f, 7f, 0.5f, 0.5f), sent)
        assertEquals(0f, msgs[0].args[0], 0f)
        assertEquals(1f, msgs[1].args[0], 0f)
    }

    @Test fun aChannelThatReturnsToItsOldValueIsSentAgain() {
        val sent = fresh()
        OscOutPlan.changes(floatArrayOf(0.2f, 0f, 0f, 0f), sent)
        OscOutPlan.changes(floatArrayOf(0.8f, 0f, 0f, 0f), sent)
        assertEquals(listOf("/studio/a"), OscOutPlan.changes(floatArrayOf(0.2f, 0f, 0f, 0f), sent).map { it.address })
    }

    @Test fun aSentMessageReadsBackAsItWasSent() {
        val sent = fresh()
        // a first call sends all four channels; the first one is A
        val m = OscOutPlan.changes(floatArrayOf(0.75f, 0f, 0f, 0f), sent).first()
        assertEquals(OscMessage("/studio/a", listOf(0.75f)), OscParser.parse(OscEncoder.encode(m.address, m.args)))
    }
}
