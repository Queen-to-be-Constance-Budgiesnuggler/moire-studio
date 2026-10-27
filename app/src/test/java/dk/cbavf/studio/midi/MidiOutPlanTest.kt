package dk.cbavf.studio.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiOutPlanTest {
    private fun fresh() = IntArray(4) { -1 }
    private val ccs = MidiOutPlan.DEFAULT_CCS

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun aControllerChangeIsThreeBytesOnTheChannel() {
        assertArrayEquals(bytes(0xB0, 1, 64), MidiOutPlan.cc(1, 1, 64))
        assertArrayEquals(bytes(0xBF, 74, 127), MidiOutPlan.cc(16, 74, 127))
    }

    @Test fun zeroToOneBecomesZeroTo127() {
        assertEquals(0, MidiOutPlan.toData(0f))
        assertEquals(64, MidiOutPlan.toData(0.5f))
        assertEquals(127, MidiOutPlan.toData(1f))
    }

    @Test fun valuesOutsideZeroToOneAreClamped() {
        assertEquals(0, MidiOutPlan.toData(-2f))
        assertEquals(127, MidiOutPlan.toData(9f))
    }

    @Test fun theFirstTimeEveryJackIsSent() {
        val msgs = MidiOutPlan.changes(floatArrayOf(0f, 0.5f, 1f, 0f), ccs, 1, fresh())
        assertEquals(listOf(1, 2, 3, 4), msgs.map { it[1].toInt() })
        assertEquals(listOf(0, 64, 127, 0), msgs.map { it[2].toInt() })
    }

    @Test fun aSteadyJackSendsNothing() {
        val sent = fresh()
        MidiOutPlan.changes(floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f), ccs, 1, sent)
        assertTrue(MidiOutPlan.changes(floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f), ccs, 1, sent).isEmpty())
    }

    @Test fun aChangeTooSmallToMoveTheMidiValueIsNotSent() {
        val sent = fresh()
        MidiOutPlan.changes(floatArrayOf(0.5f, 0f, 0f, 0f), ccs, 1, sent)
        assertTrue(MidiOutPlan.changes(floatArrayOf(0.501f, 0f, 0f, 0f), ccs, 1, sent).isEmpty())
    }

    @Test fun onlyTheChangedJackIsSentAsItsController() {
        val sent = fresh()
        MidiOutPlan.changes(floatArrayOf(0f, 0f, 0f, 0f), listOf(10, 11, 12, 13), 2, sent)
        val msgs = MidiOutPlan.changes(floatArrayOf(0f, 0f, 1f, 0f), listOf(10, 11, 12, 13), 2, sent)
        assertEquals(1, msgs.size)
        assertArrayEquals(bytes(0xB1, 12, 127), msgs[0])
    }

    @Test fun whatIsSentReadsBackThroughTheParser() {
        val out = ArrayList<MidiEvent>()
        val p = MidiParser()
        MidiOutPlan.changes(floatArrayOf(1f, 0f, 0f, 0f), ccs, 3, fresh()).forEach { p.feed(it) { e -> out += e } }
        assertEquals(MidiEvent.ControlChange(2, 1, 127), out.first())
    }

    private val keys = MidiDeviceEntry(1, "Keys", sends = 1, takes = 0)
    private val synth = MidiDeviceEntry(2, "Synth", sends = 0, takes = 1)
    private val both = MidiDeviceEntry(3, "Both", sends = 1, takes = 1)

    @Test fun midiInListsOnlyDevicesThatSend() {
        assertEquals(listOf(keys, both), MidiDevices.forIn(listOf(keys, synth, both)))
    }

    @Test fun midiOutListsOnlyDevicesThatTake() {
        assertEquals(listOf(synth, both), MidiDevices.forOut(listOf(keys, synth, both)))
    }

    @Test fun thePickedDeviceIsUsedWhileItIsThere() {
        assertEquals(both, MidiDevices.pick(listOf(keys, both), picked = 3))
    }

    @Test fun withoutAPickOrWhenThePickIsGoneTheFirstDeviceIsUsed() {
        assertEquals(keys, MidiDevices.pick(listOf(keys, both), picked = null))
        assertEquals(keys, MidiDevices.pick(listOf(keys, both), picked = 9))
        assertNull(MidiDevices.pick(emptyList(), picked = 1))
    }

    @Test fun whenThePickedDeviceIsGoneTheFallbackItOpenedStaysOpen() {
        // The picked device (3) was unplugged; pick falls back to Keys, and keepOpen must agree, or the unit would reopen Keys forever.
        val fitting = listOf(keys)
        val opened = MidiDevices.pick(fitting, picked = 3)!!
        assertTrue(MidiDevices.keepOpen(fitting, picked = 3, openId = opened.id))
    }

    @Test fun pickingAnotherDeviceClosesTheOpenOne() {
        assertFalse(MidiDevices.keepOpen(listOf(keys, both), picked = 3, openId = keys.id))
    }

    @Test fun anUnpluggedOpenDeviceIsClosed() {
        assertFalse(MidiDevices.keepOpen(listOf(both), picked = null, openId = keys.id))
    }
}
