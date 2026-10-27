package dk.cbavf.studio.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg bytes: Int): List<MidiEvent> {
        val out = ArrayList<MidiEvent>()
        MidiParser().feed(ByteArray(bytes.size) { bytes[it].toByte() }) { out += it }
        return out
    }

    @Test fun aControlChangeReadsItsChannelControllerAndValue() {
        assertEquals(listOf(MidiEvent.ControlChange(2, 7, 100)), parse(0xB2, 7, 100))
    }

    @Test fun aNoteOnReadsItsNoteAndVelocity() {
        assertEquals(listOf(MidiEvent.NoteOn(0, 60, 90)), parse(0x90, 60, 90))
    }

    @Test fun aNoteOnWithVelocityZeroIsANoteOff() {
        assertEquals(listOf(MidiEvent.NoteOff(0, 60)), parse(0x90, 60, 0))
    }

    @Test fun aNoteOffReadsAsANoteOff() {
        assertEquals(listOf(MidiEvent.NoteOff(15, 64)), parse(0x8F, 64, 40))
    }

    @Test fun runningStatusReusesTheLastStatusByte() {
        assertEquals(
            listOf(MidiEvent.ControlChange(0, 1, 10), MidiEvent.ControlChange(0, 1, 20), MidiEvent.ControlChange(0, 2, 30)),
            parse(0xB0, 1, 10, 1, 20, 2, 30),
        )
    }

    @Test fun aRealTimeByteInTheMiddleOfAMessageDoesNotBreakIt() {
        assertEquals(listOf(MidiEvent.NoteOn(0, 60, 90)), parse(0x90, 60, 0xF8, 90))
    }

    @Test fun systemExclusiveIsSkipped() {
        assertEquals(listOf(MidiEvent.ControlChange(0, 1, 5)), parse(0xF0, 0x7E, 1, 2, 3, 0xF7, 0xB0, 1, 5))
    }

    @Test fun dataAfterSystemExclusiveWithoutANewStatusIsIgnored() {
        assertTrue(parse(0xF0, 1, 2, 0xF7, 1, 2).isEmpty())
    }

    @Test fun aMessageSplitAcrossTwoPacketsStillReadsWhole() {
        val p = MidiParser()
        val out = ArrayList<MidiEvent>()
        p.feed(byteArrayOf(0xB0.toByte(), 74)) { out += it }
        assertTrue(out.isEmpty())
        p.feed(byteArrayOf(64)) { out += it }
        assertEquals(listOf(MidiEvent.ControlChange(0, 74, 64)), out)
    }

    @Test fun programChangeAndPitchBendAreReadButNotReported() {
        // A program change takes one data byte and a pitch bend two; the note after each still reads.
        assertEquals(listOf(MidiEvent.NoteOn(0, 61, 1)), parse(0xC0, 5, 0xE0, 0, 64, 0x90, 61, 1))
    }

    @Test fun strayDataBytesBeforeAnyStatusAreIgnored() {
        assertEquals(listOf(MidiEvent.NoteOn(1, 62, 70)), parse(10, 20, 0x91, 62, 70))
    }

    @Test fun onlyThePartOfTheBufferAskedForIsRead() {
        val out = ArrayList<MidiEvent>()
        val bytes = byteArrayOf(0, 0xB0.toByte(), 3, 9, 0x90.toByte())
        MidiParser().feed(bytes, offset = 1, length = 3) { out += it }
        assertEquals(listOf(MidiEvent.ControlChange(0, 3, 9)), out)
    }
}
