package dk.cbavf.studio.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import dk.cbavf.studio.routing.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The MIDI in and MIDI out rack units (android.media.midi). Each is switched on from its back, where the devices are listed. While a
 * unit is on and the studio is in front, one device is open: MIDI in reads its messages into the "midi" jacks ([MidiInMap]), MIDI out
 * sends the "midiout" jacks to it as controller changes ([MidiOutPlan]). While the studio is off screen ([RunGate]) or the unit is
 * off, the device is closed and nothing listens; it is opened again when the studio is back.
 */
class MidiBus(private val context: Context) {
    var inOn by mutableStateOf(false)
    var outOn by mutableStateOf(false)

    /** The MIDI devices there are now (refreshed when a unit is switched on, and while one is open). */
    var devices by mutableStateOf<List<MidiDeviceEntry>>(emptyList())

    /** The device picked for each unit; null, or gone, uses the first that fits. */
    var inPicked by mutableStateOf<Int?>(null)
    var outPicked by mutableStateOf<Int?>(null)

    /** What each unit did last, for its back panel. */
    var inLast by mutableStateOf("")
    var outLast by mutableStateOf("")

    /** The device each unit has open, or null. */
    var inOpen by mutableStateOf<MidiDeviceEntry?>(null)
    var outOpen by mutableStateOf<MidiDeviceEntry?>(null)

    var inSources by mutableStateOf(MidiInMap.DEFAULT_SOURCES)
    var inChannel by mutableIntStateOf(0)
    var outCcs by mutableStateOf(MidiOutPlan.DEFAULT_CCS)
    var outChannel by mutableIntStateOf(1)

    private val manager: MidiManager? by lazy { context.getSystemService(MidiManager::class.java) }
    private var infos: Map<Int, MidiDeviceInfo> = emptyMap()

    /** False on phones without MIDI support. */
    val available: Boolean get() = manager != null

    /** Lists the devices again. */
    fun refresh() {
        val all = manager?.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).orEmpty()
        infos = all.associateBy { it.id }
        devices = all.map { d ->
            val name = d.properties.getString(MidiDeviceInfo.PROPERTY_NAME)?.takeIf { it.isNotBlank() } ?: "MIDI device ${d.id}"
            MidiDeviceEntry(d.id, name, sends = d.outputPortCount, takes = d.inputPortCount)
        }.sortedBy { it.name }
    }

    suspend fun runIn(router: Router, gate: RunGate = Foreground.gate) {
        val map = MidiInMap()
        // One look at what is plugged in, so the backs can list it; nothing is opened until a unit is on.
        refresh()
        while (true) {
            if (!inOn || !gate.isStarted) {
                gate.sleep(300)
                continue
            }
            val m = manager
            if (m == null) {
                inLast = "no MIDI on this phone"
                inOn = false
                continue
            }
            refresh()
            val entry = MidiDevices.pick(MidiDevices.forIn(devices), inPicked)
            if (entry == null) {
                inLast = "no device to read from"
                inOn = false
                continue
            }
            val device = open(m, entry)
            if (device == null) {
                inLast = "could not open ${entry.name}"
                inOn = false
                continue
            }
            val port = device.openOutputPort(0)
            if (port == null) {
                device.close()
                inLast = "${entry.name} is busy"
                inOn = false
                continue
            }
            // Messages arrive on a MIDI thread; they are queued and read here, where the router is written.
            val queue = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)
            val receiver = object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    queue.trySend(msg.copyOfRange(offset, offset + count))
                }
            }
            port.connect(receiver)
            val watch = watchDevices(m)
            inOpen = entry
            inLast = ""
            val parser = MidiParser()
            var channel = inChannel
            try {
                while (inOn && gate.isStarted &&MidiDevices.keepOpen(MidiDevices.forIn(devices), inPicked, entry.id)) {
                    var changed = false
                    // Another channel: keys held on the old one would never be let go.
                    if (channel != inChannel) {
                        channel = inChannel
                        map.releaseAll()
                        changed = true
                    }
                    // Polled, never a cancelled receive: a message taken off the queue is always read.
                    val bytes = queue.tryReceive().getOrNull()
                    if (bytes != null) {
                        parser.feed(bytes) { e ->
                            if (map.apply(e, inSources, channel)) changed = true
                            inLast = describe(e)
                        }
                    }
                    if (changed) publishIn(router, map)
                    if (bytes == null) delay(10)
                }
            } finally {
                m.unregisterDeviceCallback(watch)
                port.disconnect(receiver)
                runCatching { port.close() }
                runCatching { device.close() }
                inOpen = null
                // The device's note offs will not come now.
                map.releaseAll()
                publishIn(router, map)
            }
        }
    }

    suspend fun runOut(router: Router, gate: RunGate = Foreground.gate) {
        val sent = IntArray(MIDI_JACKS.size) { -1 }
        while (true) {
            if (!outOn || !gate.isStarted) {
                gate.sleep(300)
                continue
            }
            val m = manager
            if (m == null) {
                outLast = "no MIDI on this phone"
                outOn = false
                continue
            }
            refresh()
            val entry = MidiDevices.pick(MidiDevices.forOut(devices), outPicked)
            if (entry == null) {
                outLast = "no device to send to"
                outOn = false
                continue
            }
            val device = open(m, entry)
            if (device == null) {
                outLast = "could not open ${entry.name}"
                outOn = false
                continue
            }
            val port = device.openInputPort(0)
            if (port == null) {
                device.close()
                outLast = "${entry.name} is busy"
                outOn = false
                continue
            }
            val watch = watchDevices(m)
            outOpen = entry
            outLast = ""
            sent.fill(-1)
            var ccs = outCcs
            var channel = outChannel
            try {
                while (outOn && gate.isStarted &&MidiDevices.keepOpen(MidiDevices.forOut(devices), outPicked, entry.id)) {
                    // A new mapping sends every jack again.
                    if (ccs != outCcs || channel != outChannel) { ccs = outCcs; channel = outChannel; sent.fill(-1) }
                    val values = FloatArray(MIDI_JACKS.size) { router.level("midiout." + MIDI_JACKS[it]) }
                    val msgs = MidiOutPlan.changes(values, ccs, channel, sent)
                    if (msgs.isNotEmpty()) {
                        val ok = withContext(Dispatchers.IO) { runCatching { msgs.forEach { port.send(it, 0, it.size) } }.isSuccess }
                        outLast = if (ok) msgs.last().let { "CC ${it[1]} = ${it[2]}" } else "could not send"
                    }
                    delay(20)
                }
            } finally {
                m.unregisterDeviceCallback(watch)
                runCatching { port.close() }
                runCatching { device.close() }
                outOpen = null
            }
        }
    }

    private fun publishIn(router: Router, map: MidiInMap) {
        MIDI_JACKS.forEachIndexed { i, j -> router.publishLevel("midi.$j", map.values[i]) }
        router.publishLevel("midi.gate", if (map.gate) 1f else 0f)
    }

    private fun describe(e: MidiEvent) = "Ch ${e.channel + 1} " + when (e) {
        is MidiEvent.NoteOn -> "note ${e.note} on, velocity ${e.velocity}"
        is MidiEvent.NoteOff -> "note ${e.note} off"
        is MidiEvent.ControlChange -> "CC ${e.controller} = ${e.value}"
    }

    /** Keeps [devices] current while a device is open, so a device that is unplugged closes its unit's port. */
    private fun watchDevices(m: MidiManager): MidiManager.DeviceCallback {
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = refresh()
            override fun onDeviceRemoved(device: MidiDeviceInfo) = refresh()
        }
        m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, context.mainExecutor, cb)
        return cb
    }

    private suspend fun open(m: MidiManager, entry: MidiDeviceEntry): MidiDevice? {
        val info = infos[entry.id] ?: return null
        return suspendCancellableCoroutine { c ->
            m.openDevice(info, { d -> if (c.isActive) c.resume(d) else d?.close() }, Handler(Looper.getMainLooper()))
        }
    }
}
