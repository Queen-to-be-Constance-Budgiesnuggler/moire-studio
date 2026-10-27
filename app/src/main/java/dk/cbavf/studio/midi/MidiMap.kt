package dk.cbavf.studio.midi

import kotlin.math.roundToInt

/** The four value jacks of each MIDI unit, A to D. */
val MIDI_JACKS = listOf("a", "b", "c", "d")

/**
 * The MIDI in unit's mapping: each of A to D follows one controller (CC 0 to 127) or, with [VELOCITY], the velocity of the last note,
 * scaled 0 to 1. The gate is on while any key is held. A channel of 0 listens to all sixteen; 1 to 16 listens to that one only.
 */
class MidiInMap {
    /** The four jack values, 0 to 1. */
    val values = FloatArray(MIDI_JACKS.size)

    private val held = HashSet<Int>()

    /** True while a key is held. */
    val gate: Boolean get() = held.isNotEmpty()

    /** Takes one message; returns true if a jack or the gate changed. [sources] has one entry per jack. */
    fun apply(e: MidiEvent, sources: List<Int>, channel: Int = 0): Boolean {
        if (channel != 0 && e.channel != channel - 1) return false
        val wasGate = gate
        var changed = false
        fun set(source: Int, raw: Int) {
            for (i in values.indices) {
                if (sources.getOrNull(i) != source) continue
                val v = raw.coerceIn(0, 127) / 127f
                if (values[i] != v) { values[i] = v; changed = true }
            }
        }
        when (e) {
            is MidiEvent.NoteOn -> { held += e.channel * 128 + e.note; set(VELOCITY, e.velocity) }
            is MidiEvent.NoteOff -> held -= e.channel * 128 + e.note
            is MidiEvent.ControlChange -> {
                // All sound off and all notes off let every key go.
                if (e.controller == 120 || e.controller == 123) held.removeAll { it / 128 == e.channel }
                set(e.controller, e.value)
            }
        }
        return changed || wasGate != gate
    }

    /** Forgets held keys (the device was closed: its note offs will not come). The values stay where they were. */
    fun releaseAll() = held.clear()

    companion object {
        /** A source that is not a controller: the velocity of the last note. */
        const val VELOCITY = 128

        /** A, B and C follow the mod wheel, the breath controller and CC 3; D follows how hard the last key was struck. */
        val DEFAULT_SOURCES = listOf(1, 2, 3, VELOCITY)

        /** How a source reads on the unit's back. */
        fun sourceLabel(source: Int) = if (source == VELOCITY) "Velocity" else "CC $source"

        fun channelLabel(channel: Int) = if (channel == 0) "All" else "Ch $channel"
    }
}

/** What the MIDI out unit sends: A to D, each 0 to 1, go out as controller changes 0 to 127 on one channel (1 to 16). */
object MidiOutPlan {
    /** CC 1 to 4 to start with. */
    val DEFAULT_CCS = listOf(1, 2, 3, 4)

    /** A value 0 to 1 as a MIDI data byte. */
    fun toData(v: Float): Int = (v.coerceIn(0f, 1f) * 127).roundToInt()

    /** The three bytes of one controller change; [channel] is 1 to 16. */
    fun cc(channel: Int, controller: Int, value: Int): ByteArray =
        byteArrayOf((0xB0 or ((channel - 1).coerceIn(0, 15))).toByte(), controller.coerceIn(0, 127).toByte(), value.coerceIn(0, 127).toByte())

    /**
     * The messages to send now for [values] (one per jack), given the data bytes [sent] last (-1: never sent). Only a jack whose
     * 0 to 127 value changed is sent, so a steady jack is quiet. [sent] is updated for every message returned.
     */
    fun changes(values: FloatArray, ccs: List<Int>, channel: Int, sent: IntArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(0)
        for (i in MIDI_JACKS.indices) {
            val d = toData(values[i])
            if (d != sent[i]) {
                out += cc(channel, ccs[i], d)
                sent[i] = d
            }
        }
        return out
    }
}

/** A MIDI device as the unit backs list it. [sends]: ports it sends from (for MIDI in); [takes]: ports it takes (for MIDI out). */
data class MidiDeviceEntry(val id: Int, val name: String, val sends: Int, val takes: Int)

object MidiDevices {
    /** Devices MIDI in can read from. */
    fun forIn(all: List<MidiDeviceEntry>) = all.filter { it.sends > 0 }

    /** Devices MIDI out can send to. */
    fun forOut(all: List<MidiDeviceEntry>) = all.filter { it.takes > 0 }

    /** The device to use: the one picked, while it is there, or else the first that fits. */
    fun pick(fitting: List<MidiDeviceEntry>, picked: Int?): MidiDeviceEntry? = fitting.firstOrNull { it.id == picked } ?: fitting.firstOrNull()

    /** Should the device [openId] stay open? Only while it is still the one [pick] would choose, so the two never disagree. */
    fun keepOpen(fitting: List<MidiDeviceEntry>, picked: Int?, openId: Int): Boolean = pick(fitting, picked)?.id == openId
}
