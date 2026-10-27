package dk.cbavf.studio.routing.model

import kotlinx.serialization.Serializable

/**
 * Studio routing (docs/ARCHITECTURE.md §13): the studio's modules as units in a rack, with jacks
 * on their backs. A cable carries one module's output live into another module's input, the way
 * Reason patches its devices. Jack ids are "<module>.<jack>".
 */
enum class JackType(val argb: Long, val label: String) {
    Canvas(0xFFE89A3C, "canvas"),  // a Draw recipe: vector layers
    Image(0xFFE0C83A, "image"),    // pixels
    Value(0xFFA1A1A1, "value"),    // a number
    Trigger(0xFFF2F2F2, "trigger"), // a moment (1 for one frame)
    Gate(0xFF4FC3D9, "gate");      // on or off for a while

    /**
     * May an output of type [from] feed this input? A canvas is rendered on the way into an image input; a value input takes
     * a trigger or a gate as the number 1 or 0; a trigger and a gate each take only their own kind.
     */
    fun accepts(from: JackType) =
        from == this || (this == Image && from == Canvas) || (this == Value && (from == Trigger || from == Gate))
}

data class JackDef(val id: String, val label: String, val type: JackType)

/** One module of the studio as a rack unit: what it sends out and what it can take in. */
data class ModuleDef(
    val id: String,
    val title: String,
    val outputs: List<JackDef>,
    val inputs: List<JackDef>,
    /** What the unit's front says it does. */
    val blurb: String,
)

/** A patch cable: output jack → input jack. */
@Serializable
data class Cable(val from: String, val to: String)

/** The studio's patching, saved with the project. An input takes one cable; an output feeds any number. */
@Serializable
data class RoutingDoc(val cables: List<Cable> = emptyList()) {
    fun sourceOf(input: String): String? = cables.firstOrNull { it.to == input }?.from

    fun targetsOf(output: String): List<String> = cables.filter { it.from == output }.map { it.to }

    val patchedOutputs: Set<String> get() = cables.map { it.from }.toSet()

    /**
     * Adds a cable if the jacks fit, replacing whatever fed that input, and refusing cables
     * that would feed a module from itself (directly or round a loop of modules).
     */
    fun connect(from: String, to: String, rack: Rack = Rack): RoutingDoc {
        val out = rack.output(from) ?: return this
        val inp = rack.input(to) ?: return this
        if (!inp.type.accepts(out.type)) return this
        if (rack.moduleOf(from) == rack.moduleOf(to)) return this
        val without = cables.filterNot { it.to == to }
        if (RoutingDoc(without).feeds(rack.moduleOf(to), rack.moduleOf(from), rack)) return this
        return copy(cables = without + Cable(from, to))
    }

    fun disconnect(input: String): RoutingDoc = copy(cables = cables.filterNot { it.to == input })

    fun remove(cable: Cable): RoutingDoc = copy(cables = cables - cable)

    /** Does module [a] (transitively) feed module [b]? */
    fun feeds(a: String, b: String, rack: Rack = Rack): Boolean {
        val seen = HashSet<String>()
        val todo = ArrayDeque(listOf(a))
        while (todo.isNotEmpty()) {
            val m = todo.removeFirst()
            if (m == b) return true
            if (!seen.add(m)) continue
            cables.filter { rack.moduleOf(it.from) == m }.forEach { todo.addLast(rack.moduleOf(it.to)) }
        }
        return false
    }
}

/** Every module of the studio, as rack units, top to bottom. */
open class Rack(val modules: List<ModuleDef>) {
    fun moduleOf(jack: String): String = jack.substringBefore('.')
    fun output(id: String): JackDef? = modules.firstOrNull { it.id == moduleOf(id) }?.outputs?.firstOrNull { it.id == id }
    fun input(id: String): JackDef? = modules.firstOrNull { it.id == moduleOf(id) }?.inputs?.firstOrNull { it.id == id }
    fun jack(id: String): JackDef? = output(id) ?: input(id)

    companion object : Rack(standardModules())
}

/** Compose's live layer inputs. */
val COMPOSE_INPUTS = listOf("compose.in1", "compose.in2", "compose.in3", "compose.in4")

/** The Calculator room's last result. */
const val CALC_RESULT = "calc.result"

/** The Physics room's hits: a trigger on each step with a hit, how hard the last one was (0 to 1), and how many so far. */
const val PHYSICS_HIT = "physics.hit"
const val PHYSICS_STRENGTH = "physics.strength"
const val PHYSICS_HITS = "physics.hits"

/** Draw's image-layer input: a live image layer on the canvas. */
const val DRAW_IMAGE_IN = "draw.in"

/** The Sequence projector's input: shown full screen instead of the timeline. */
const val SEQUENCE_IN = "sequence.in"

/** The Loop projector's input: the frame the loop starts from (and steps through instead of the Sequence). */
const val LOOP_IN = "loop.in"

/** The picture a scope plugin shows. */
const val PLUGINS_SCOPE_IN = "plugins.in"

/** The Patch bay's own signals, 0 to 1: its two LFOs and the touch pad (always), and its first routes (while the patch is live). */
const val PATCH_LFO1 = "patch.lfo1"
const val PATCH_LFO2 = "patch.lfo2"
const val PATCH_TOUCH_X = "patch.touchx"
const val PATCH_TOUCH_Y = "patch.touchy"
val PATCH_ROUTES = listOf("patch.r1", "patch.r2", "patch.r3", "patch.r4")

private fun standardModules(): List<ModuleDef> = listOf(
    ModuleDef("draw", "Draw", outputs = listOf(JackDef("draw.canvas", "Canvas", JackType.Canvas)),
        inputs = listOf(JackDef(DRAW_IMAGE_IN, "Image layer", JackType.Image)),
        blurb = "The canvas, live: every stroke and slider. A picture patched into Image layer becomes a live image layer."),
    ModuleDef("animate", "Animate", outputs = listOf(JackDef("animate.frame", "Current frame", JackType.Canvas)), inputs = emptyList(),
        blurb = "The frame on show (also while playing)."),
    ModuleDef("compose", "Compose", outputs = listOf(JackDef("compose.image", "Composite", JackType.Image)),
        inputs = COMPOSE_INPUTS.mapIndexed { i, id -> JackDef(id, "Layer ${i + 1}", JackType.Image) },
        blurb = "Inputs become live layers; the composite goes out."),
    ModuleDef("develop", "Develop", outputs = listOf(JackDef("develop.image", "Developed", JackType.Image)),
        inputs = listOf(JackDef("develop.in", "Source", JackType.Image)), blurb = "Develops whatever comes in, live."),
    ModuleDef("fft", "FFT lab", outputs = listOf(JackDef("fft.image", "Result", JackType.Image)),
        inputs = listOf(JackDef("fft.in", "Source", JackType.Image)), blurb = "Filters whatever comes in, live."),
    ModuleDef("glitch", "Glitch lab", outputs = listOf(JackDef("glitch.image", "Result", JackType.Image)),
        inputs = listOf(JackDef("glitch.in", "Source", JackType.Image), JackDef("glitch.seed", "Seed", JackType.Value)),
        blurb = "Glitches whatever comes in, live. A number patched into Seed replaces the corruption seed (the Sensors unit's Seed lets the world corrupt the picture)."),
    ModuleDef("camera", "Camera", outputs = listOf(JackDef("camera.last", "Last capture", JackType.Image)),
        inputs = listOf(JackDef("camera.overlay", "Overlay", JackType.Image)), blurb = "An input shows over the preview, to line up a shot."),
    ModuleDef("sequence", "Sequence", outputs = emptyList(), inputs = listOf(JackDef(SEQUENCE_IN, "Projector", JackType.Image)),
        blurb = "A picture patched into Projector is shown full screen, live, when you press Projector."),
    ModuleDef("loop", "Loop link", outputs = listOf(JackDef("loop.remote", "From other phone", JackType.Image)),
        inputs = listOf(JackDef(LOOP_IN, "Projector", JackType.Image)),
        blurb = "The latest picture from the linked phone. A picture patched into Projector is the first frame of the loop, and takes the place of the Sequence."),
    ModuleDef("ab", "A/B compare", outputs = emptyList(),
        inputs = listOf(JackDef("ab.a", "A", JackType.Image), JackDef("ab.b", "B", JackType.Image)),
        blurb = "Hold a canvas to see A and B split, or B in full. Without cables A is the room and B is what Snap B kept."),
    ModuleDef("nodes", "Node Lab", outputs = listOf(JackDef("nodes.out", "Viewer", JackType.Image)),
        inputs = listOf(
            JackDef("nodes.in1", "In 1", JackType.Image), JackDef("nodes.in2", "In 2", JackType.Image),
            JackDef("nodes.sig1", "Signal 1", JackType.Value), JackDef("nodes.sig2", "Signal 2", JackType.Value),
        ),
        blurb = "Inputs feed Module In and Module Signal nodes; the Viewer goes out."),
    ModuleDef("osc", "OSC in", outputs = listOf(
        JackDef("osc.a", "A", JackType.Value), JackDef("osc.b", "B", JackType.Value), JackDef("osc.c", "C", JackType.Value), JackDef("osc.d", "D", JackType.Value),
    ), inputs = emptyList(), blurb = "Open Sound Control on UDP port 9000: a message to /a, /b, /c or /d (or /1 to /4) sets that jack to its first number, 0 to 1. Switch it on from this unit's back."),
    ModuleDef("oscout", "OSC out", outputs = emptyList(), inputs = listOf(
        JackDef("oscout.a", "A", JackType.Value), JackDef("oscout.b", "B", JackType.Value), JackDef("oscout.c", "C", JackType.Value), JackDef("oscout.d", "D", JackType.Value),
    ), blurb = "Sends whatever is patched into A to D as OSC to /studio/a to /studio/d (0 to 1), to the host and port set in Config. Switch it on from this unit's back."),
    ModuleDef("midi", "MIDI in", outputs = listOf(
        JackDef("midi.a", "A", JackType.Value), JackDef("midi.b", "B", JackType.Value), JackDef("midi.c", "C", JackType.Value), JackDef("midi.d", "D", JackType.Value),
        JackDef("midi.gate", "Gate", JackType.Gate),
    ), inputs = emptyList(), blurb = "A MIDI keyboard or controller. A to D follow a controller (CC) or how hard the last key was struck, 0 to 1. Gate is on while a key is held. Pick the device and switch it on from this unit's back."),
    ModuleDef("midiout", "MIDI out", outputs = emptyList(), inputs = listOf(
        JackDef("midiout.a", "A", JackType.Value), JackDef("midiout.b", "B", JackType.Value), JackDef("midiout.c", "C", JackType.Value), JackDef("midiout.d", "D", JackType.Value),
    ), blurb = "Sends whatever is patched into A to D as MIDI controllers (CC 1 to 4 to start with) to a MIDI device. Pick the device and switch it on from this unit's back."),
    ModuleDef("slitscan", "Slit-scan", outputs = emptyList(), inputs = listOf(JackDef("slitscan.in", "Stream", JackType.Image)),
        blurb = "A picture patched into Stream is collected, one frame each time it changes, to take the slits from (Slit-scan room, source: Stream)."),
    ModuleDef("plugins", "Plugins", outputs = emptyList(), inputs = listOf(JackDef(PLUGINS_SCOPE_IN, "Scope in", JackType.Image)),
        blurb = "A picture patched into Scope in is what a scope plugin shows (the RGB parade, in the Plugins room)."),
    ModuleDef("sensors", "Sensors", outputs = listOf(
        JackDef("sensors.roll", "Roll", JackType.Value), JackDef("sensors.pitch", "Pitch", JackType.Value), JackDef("sensors.heading", "Heading", JackType.Value),
        JackDef("sensors.shake", "Shake", JackType.Value), JackDef("sensors.light", "Light", JackType.Value), JackDef("sensors.mic", "Mic level", JackType.Value),
        JackDef("sensors.seed", "Seed", JackType.Value),
    ), inputs = emptyList(), blurb = "The phone's sensors, scaled 0 to 1 (Seed is a whole number mixed from them, refreshed twice a second). Switch them on from this unit's back."),
    ModuleDef("patch", "Patch bay", outputs = listOf(
        JackDef(PATCH_LFO1, "LFO 1", JackType.Value), JackDef(PATCH_LFO2, "LFO 2", JackType.Value),
        JackDef(PATCH_TOUCH_X, "Touch X", JackType.Value), JackDef(PATCH_TOUCH_Y, "Touch Y", JackType.Value),
    ) + PATCH_ROUTES.mapIndexed { i, id -> JackDef(id, "Route ${i + 1}", JackType.Value) }, inputs = emptyList(),
        blurb = "The Patch bay's signals, 0 to 1. The LFOs and the touch pad always run. Route 1 to 4 are the first four routes, where each sits in its range, while the patch is live."),
    ModuleDef("channels", "Channel splitter", outputs = listOf(
        JackDef("channels.c1", "Channel 1", JackType.Image), JackDef("channels.c2", "Channel 2", JackType.Image),
        JackDef("channels.c3", "Channel 3", JackType.Image), JackDef("channels.c4", "Channel 4", JackType.Image),
    ), inputs = listOf(JackDef("channels.in", "Source", JackType.Image)),
        blurb = "Splits whatever comes in into one grey plane per colour channel (RGBA: R G B A, CMYK: C M Y K, Lab: L a b): one picture fans out to four jacks."),
    ModuleDef("gates", "Studio gates", outputs = listOf(
        JackDef("gates.a", "Gate A", JackType.Gate), JackDef("gates.b", "Gate B", JackType.Gate), JackDef("gates.clock", "Clock", JackType.Gate),
    ), inputs = emptyList(), blurb = "Two switches and a clock, on the Studio's back: patch a gate into Node Lab or anywhere a gate or value fits."),
    ModuleDef("calc", "Calculator", outputs = listOf(JackDef(CALC_RESULT, "Result", JackType.Value)), inputs = emptyList(),
        blurb = "The Calculator's last result, as it is (not scaled to 0 to 1)."),
    ModuleDef("physics", "Physics", outputs = listOf(
        JackDef(PHYSICS_HIT, "Hit", JackType.Trigger), JackDef(PHYSICS_STRENGTH, "Strength", JackType.Value),
        JackDef(PHYSICS_HITS, "Hits", JackType.Value),
    ), inputs = emptyList(),
        blurb = "While the Physics room runs: Hit fires when two bodies meet, Strength is how hard the last hit was (0 to 1), Hits counts them (not scaled)."),
)
