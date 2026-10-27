package dk.cbavf.studio.midi

/** The MIDI messages the studio reads. [channel] is 0 to 15 (shown to people as 1 to 16). */
sealed interface MidiEvent {
    val channel: Int

    data class NoteOn(override val channel: Int, val note: Int, val velocity: Int) : MidiEvent
    data class NoteOff(override val channel: Int, val note: Int) : MidiEvent
    data class ControlChange(override val channel: Int, val controller: Int, val value: Int) : MidiEvent
}

/**
 * Reads a MIDI byte stream into [MidiEvent]s, one byte at a time, so a message split across packets still reads whole. Knows running
 * status (data bytes that reuse the last status), skips system exclusive and system messages, and lets real-time bytes (clock, start,
 * stop) pass through the middle of a message without breaking it. A note on with velocity 0 reads as a note off.
 */
class MidiParser {
    private var status = 0
    private val data = IntArray(2)
    private var count = 0

    /** Feeds [length] bytes from [offset] and hands every complete message to [out]. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, out: (MidiEvent) -> Unit) {
        for (i in offset until offset + length) feed(bytes[i].toInt() and 0xFF)?.let(out)
    }

    /** Feeds one byte (0 to 255); returns the message it completes, if any. */
    fun feed(b: Int): MidiEvent? {
        when {
            // Real time: clock, start, stop and the like. They may come between any two bytes and change nothing.
            b >= 0xF8 -> return null
            // System exclusive, system common and end of exclusive: skip them and the data bytes after them.
            b >= 0xF0 -> { status = 0; count = 0; return null }
            b >= 0x80 -> { status = b; count = 0; return null }
        }
        if (status == 0) return null
        data[count++] = b
        val needed = when (status and 0xF0) { 0xC0, 0xD0 -> 1; else -> 2 }
        if (count < needed) return null
        count = 0
        val ch = status and 0x0F
        return when (status and 0xF0) {
            0x90 -> if (data[1] == 0) MidiEvent.NoteOff(ch, data[0]) else MidiEvent.NoteOn(ch, data[0], data[1])
            0x80 -> MidiEvent.NoteOff(ch, data[0])
            0xB0 -> MidiEvent.ControlChange(ch, data[0], data[1])
            else -> null // program change, pressure, pitch bend: not used
        }
    }
}
