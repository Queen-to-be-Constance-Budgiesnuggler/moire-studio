package dk.cbavf.studio.midi

import dk.cbavf.studio.routing.model.JackType
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.routing.model.RoutingDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiInMapTest {
    private val sources = MidiInMap.DEFAULT_SOURCES

    @Test fun aControllerSetsTheJackThatFollowsIt() {
        val m = MidiInMap()
        assertTrue(m.apply(MidiEvent.ControlChange(0, 2, 127), sources))
        assertEquals(listOf(0f, 1f, 0f, 0f), m.values.toList())
    }

    @Test fun controllerValuesAreScaledZeroToOne() {
        val m = MidiInMap()
        m.apply(MidiEvent.ControlChange(0, 1, 64), sources)
        assertEquals(64 / 127f, m.values[0], 1e-6f)
    }

    @Test fun aControllerNoJackFollowsChangesNothing() {
        val m = MidiInMap()
        assertFalse(m.apply(MidiEvent.ControlChange(0, 74, 100), sources))
        assertEquals(listOf(0f, 0f, 0f, 0f), m.values.toList())
    }

    @Test fun theVelocityJackTakesHowHardTheKeyWasStruck() {
        val m = MidiInMap()
        m.apply(MidiEvent.NoteOn(0, 60, 127), sources)
        assertEquals(1f, m.values[3], 0f)
    }

    @Test fun theVelocityStaysAfterTheKeyIsLetGo() {
        val m = MidiInMap()
        m.apply(MidiEvent.NoteOn(0, 60, 127), sources)
        m.apply(MidiEvent.NoteOff(0, 60), sources)
        assertEquals(1f, m.values[3], 0f)
    }

    @Test fun twoJacksMayFollowTheSameController() {
        val m = MidiInMap()
        m.apply(MidiEvent.ControlChange(0, 7, 127), listOf(7, 7, 1, 2))
        assertEquals(listOf(1f, 1f, 0f, 0f), m.values.toList())
    }

    @Test fun theGateIsOnWhileAKeyIsHeld() {
        val m = MidiInMap()
        assertFalse(m.gate)
        assertTrue(m.apply(MidiEvent.NoteOn(0, 60, 10), sources))
        assertTrue(m.gate)
        assertTrue(m.apply(MidiEvent.NoteOff(0, 60), sources))
        assertFalse(m.gate)
    }

    @Test fun theGateStaysOnUntilTheLastKeyIsLetGo() {
        val m = MidiInMap()
        m.apply(MidiEvent.NoteOn(0, 60, 10), sources)
        m.apply(MidiEvent.NoteOn(0, 64, 10), sources)
        m.apply(MidiEvent.NoteOff(0, 60), sources)
        assertTrue(m.gate)
        m.apply(MidiEvent.NoteOff(0, 64), sources)
        assertFalse(m.gate)
    }

    @Test fun allNotesOffClosesTheGate() {
        val m = MidiInMap()
        m.apply(MidiEvent.NoteOn(0, 60, 10), sources)
        m.apply(MidiEvent.ControlChange(0, 123, 0), sources)
        assertFalse(m.gate)
    }

    @Test fun aNoteOffForAKeyNotHeldChangesNothing() {
        assertFalse(MidiInMap().apply(MidiEvent.NoteOff(0, 60), sources))
    }

    @Test fun aChosenChannelIgnoresTheOthers() {
        val m = MidiInMap()
        assertFalse(m.apply(MidiEvent.ControlChange(1, 1, 127), sources, channel = 1))
        assertTrue(m.apply(MidiEvent.ControlChange(0, 1, 127), sources, channel = 1))
        assertEquals(1f, m.values[0], 0f)
    }

    @Test fun channelZeroListensToAllSixteen() {
        val m = MidiInMap()
        assertTrue(m.apply(MidiEvent.ControlChange(15, 1, 127), sources, channel = 0))
    }

    @Test fun releaseAllClosesTheGateAndKeepsTheValues() {
        val m = MidiInMap()
        m.apply(MidiEvent.NoteOn(0, 60, 127), sources)
        m.releaseAll()
        assertFalse(m.gate)
        assertEquals(1f, m.values[3], 0f)
    }

    @Test fun sourcesReadPlainlyOnTheBack() {
        assertEquals("CC 1", MidiInMap.sourceLabel(1))
        assertEquals("Velocity", MidiInMap.sourceLabel(MidiInMap.VELOCITY))
        assertEquals("All", MidiInMap.channelLabel(0))
        assertEquals("Ch 3", MidiInMap.channelLabel(3))
    }

    @Test fun theMidiInGateIsAGateJackOnTheRack() {
        assertEquals(JackType.Gate, Rack.output("midi.gate")?.type)
        assertEquals(MIDI_JACKS.map { "midi.$it" }, MIDI_JACKS.map { Rack.output("midi.$it")?.id })
    }

    @Test fun midiInCanBePatchedStraightIntoMidiOut() {
        val doc = RoutingDoc().connect("midi.a", "midiout.a")
        assertEquals("midi.a", doc.sourceOf("midiout.a"))
    }
}
