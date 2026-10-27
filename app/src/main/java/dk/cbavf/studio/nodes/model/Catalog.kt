package dk.cbavf.studio.nodes.model

import dk.cbavf.studio.math.FmAmMode
import dk.cbavf.studio.patch.model.LfoShape
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.SourceKind

/** A socket on a node. Value inputs carry a default and a range for their inline field. */
data class SocketDef(
    val name: String,
    val type: SocketType,
    val default: Float = 0f,
    val min: Float = 0f,
    val max: Float = 1f,
    /** Whole numbers only (rays, levels, seeds). */
    val whole: Boolean = false,
)

/** A property shown on the node body: a choice among options, or text (an asset id). */
data class PropDef(val name: String, val options: List<String> = emptyList(), val default: String = options.firstOrNull() ?: "")

enum class NodeCategory(val label: String, val argb: Long) {
    Input("Input", 0xFFB0503A),
    Generator("Generator", 0xFF8E4A9E),
    Color("Colour", 0xFF3E6E9E),
    Filter("Filter", 0xFF4A7F6F),
    Converter("Converter", 0xFF4A6FA5),
    Time("Time", 0xFF9A6B1F),
    Layout("Layout", 0xFF5A5A5A),
    Group("Group", 0xFF2F7A55),
    Output("Output", 0xFF7A3A3A),
}

data class NodeDef(
    val type: String,
    val label: String,
    val category: NodeCategory,
    val inputs: List<SocketDef> = emptyList(),
    val outputs: List<SocketDef> = emptyList(),
    val props: List<PropDef> = emptyList(),
    /** Sinks end a pipeline; Save and Send run on demand. */
    val sink: Boolean = false,
    val help: String = "",
)

/** Every node type Node Lab knows. */
open class Catalog(private val defs: List<NodeDef>) {
    open operator fun get(type: String): NodeDef? = byType[type]
    open val all: List<NodeDef> get() = defs
    /** Node groups this catalog knows (see [GroupCatalog]). */
    open val groups: List<NodeGroup> get() = emptyList()
    private val byType = defs.associateBy { it.type }

    companion object : Catalog(standardNodes())
}

private fun img(name: String = "Image") = SocketDef(name, SocketType.Image)
private fun num(name: String, default: Float, min: Float, max: Float, whole: Boolean = false) = SocketDef(name, SocketType.Value, default, min, max, whole)
private fun sig(name: String) = SocketDef(name, SocketType.Signal)
private fun gate(name: String) = SocketDef(name, SocketType.Gate, 0f, 0f, 1f)
private fun canvas(name: String = "Canvas") = SocketDef(name, SocketType.Canvas)
private fun frames() = SocketDef("Frames", SocketType.Frames)

/** Sensor node sources: the Patch bay's hardware sources (LFOs and the touch pad are their own nodes). */
val SENSOR_SOURCES: List<SourceKind> = SourceKind.entries.filter { !it.isLfo && !it.isTouch }

/** Layer parameters a Drive Layer node can set (the Patch bay's route targets). */
val DRIVE_PARAMS: List<Param> = Param.entries

val BLEND_NAMES = listOf(
    "Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "Difference", "Exclusion",
    "Add", "SoftLight", "HardLight", "ColorDodge", "ColorBurn", "Hue", "Saturation", "Color", "Luminosity",
)

/** The Mix node works on bitmaps, so it also offers the two subtract blends (blend/SubtractMath.kt). */
val MIX_BLEND_NAMES = BLEND_NAMES + listOf("Subtract", "ReverseSubtract")

private fun standardNodes(): List<NodeDef> = listOf(
    // Input
    NodeDef("image", "Library Image", NodeCategory.Input, outputs = listOf(img()), props = listOf(PropDef("asset")),
        help = "A picture from the Library (tap Pick on the node)."),
    NodeDef("draw", "Draw Canvas", NodeCategory.Input, outputs = listOf(SocketDef("Canvas", SocketType.Canvas)),
        help = "The Draw room's canvas, live."),
    NodeDef("modulesignal", "Module Signal", NodeCategory.Input, outputs = listOf(sig("Signal")), props = listOf(PropDef("Jack", listOf("Signal 1", "Signal 2"))),
        help = "A number from the Rack: patch a gate, trigger or value into Node Lab's Signal 1 or 2 (for example the Studio gates)."),
    NodeDef("modulein", "Module In", NodeCategory.Input, outputs = listOf(img()), props = listOf(PropDef("Jack", listOf("In 1", "In 2"))),
        help = "What the Rack patches into Node Lab's In 1 or In 2, live (e.g. the Draw canvas or the Compose composite)."),
    NodeDef("fromphone", "From Other Phone", NodeCategory.Input, outputs = listOf(img(), num("Count", 0f, 0f, 1f)),
        help = "The latest picture from the phone linked in Loop: its Send to Other Phone node, or a Loop capture. Count goes up with each."),
    NodeDef("value", "Value", NodeCategory.Input, inputs = listOf(num("Value", 1f, -1000f, 1000f)), outputs = listOf(num("Value", 0f, 0f, 1f))),
    // The pad's last X and Y (0..1) are kept in the node's "X" and "Y" props; Down is not saved.
    NodeDef("touchpad", "Touch Pad", NodeCategory.Input,
        inputs = listOf(num("Min", 0f, -2000f, 2000f), num("Max", 1f, -2000f, 2000f)),
        outputs = listOf(sig("X"), sig("Y"), gate("Down")),
        help = "Select the node and drag on the pad under the toolbar. X and Y go from Min to Max (Y is Min at the top). Down is on while your finger is on the pad."),

    // Generators (canvases: transparent between their lines)
    NodeDef("grating", "Grating", NodeCategory.Generator,
        inputs = listOf(num("Spacing", 10f, 2f, 80f), num("Angle", 0f, -180f, 180f), num("Line width", 0.5f, 0.05f, 0.95f), num("Offset", 0f, -100f, 100f)),
        outputs = listOf(SocketDef("Canvas", SocketType.Canvas)), props = listOf(PropDef("Profile", listOf("Lines", "Sine")))),
    NodeDef("burst", "Radial Burst", NodeCategory.Generator,
        inputs = listOf(num("Rays", 180f, 8f, 720f, whole = true), num("Rotation", 0f, -180f, 180f), num("Ray width", 0.5f, 0.05f, 0.95f)),
        outputs = listOf(SocketDef("Canvas", SocketType.Canvas))),
    NodeDef("ellipses", "Ellipse Hatch", NodeCategory.Generator,
        inputs = listOf(num("Spacing", 12f, 2f, 80f), num("Aspect", 0.6f, 0.1f, 3f), num("Rotation", 0f, -180f, 180f), num("Line width", 0.5f, 0.05f, 0.95f)),
        outputs = listOf(SocketDef("Canvas", SocketType.Canvas))),

    // Converters
    NodeDef("render", "Render Canvas", NodeCategory.Converter,
        inputs = listOf(SocketDef("Canvas", SocketType.Canvas), num("Width", 1080f, 64f, 8192f, whole = true), num("Height", 1920f, 64f, 8192f, whole = true)),
        outputs = listOf(img()), props = listOf(PropDef("Paper", listOf("White", "Transparent"))),
        help = "Draws a canvas to an image of the given size."),
    NodeDef("math", "Math", NodeCategory.Converter,
        inputs = listOf(num("A", 0f, -1000f, 1000f), num("B", 0f, -1000f, 1000f)), outputs = listOf(num("Value", 0f, 0f, 1f)),
        props = listOf(PropDef("Operation", listOf("Add", "Subtract", "Multiply", "Divide", "Power", "Minimum", "Maximum", "Sine", "Modulo")))),
    NodeDef("maprange", "Map Range", NodeCategory.Converter,
        inputs = listOf(num("Value", 0.5f, -1000f, 1000f), num("From min", 0f, -1000f, 1000f), num("From max", 1f, -1000f, 1000f), num("To min", 0f, -1000f, 1000f), num("To max", 1f, -1000f, 1000f)),
        outputs = listOf(num("Result", 0f, 0f, 1f)), props = listOf(PropDef("Clamp", listOf("On", "Off")))),
    NodeDef("switch", "Switch", NodeCategory.Converter,
        inputs = listOf(num("A", 0f, -1000f, 1000f), num("B", 1f, -1000f, 1000f), num("Select", 0f, 0f, 1f), num("Threshold", 0.5f, 0f, 1f)),
        outputs = listOf(num("Value", 0f, 0f, 1f)), props = listOf(PropDef("Mode", listOf("Pick", "Fade"))),
        help = "Pick: B when Select is at or above Threshold, else A (link a gate into Select). Fade: from A at Select 0 to B at Select 1."),
    NodeDef("stack", "Layer Canvases", NodeCategory.Converter,
        inputs = listOf(canvas("A"), canvas("B")), outputs = listOf(canvas()), props = listOf(PropDef("Blend", BLEND_NAMES)),
        help = "B's layers over A's, still vector (so Record can take it). B's layers get the blend."),
    NodeDef("drive", "Drive Layer", NodeCategory.Converter,
        inputs = listOf(canvas(), num("Layer", 1f, 1f, 99f, whole = true), num("Value", 0f, -2000f, 2000f)),
        outputs = listOf(canvas()), props = listOf(PropDef("Parameter", DRIVE_PARAMS.map { it.label })),
        help = "Sets one parameter of a layer (1 = bottom layer). Wire a Sensor or LFO into Value: a Patch bay route."),
    NodeDef("transform", "Transform", NodeCategory.Converter,
        inputs = listOf(img(), num("X", 0f, -4000f, 4000f), num("Y", 0f, -4000f, 4000f), num("Scale", 1f, 0.01f, 10f), num("Rotation", 0f, -180f, 180f)),
        outputs = listOf(img())),

    // Time (Live runs the clock; without Live the graph is shown at time 0)
    NodeDef("clock", "Clock", NodeCategory.Time,
        inputs = listOf(num("Rate", 2f, 0f, 120f), num("Steps", 0f, 0f, 9999f, whole = true)),
        outputs = listOf(sig("Seconds"), sig("Step"), sig("Phase"), SocketDef("Tick", SocketType.Trigger)),
        help = "Time since Live started. Step counts ticks at Rate per second (wrapping at Steps unless 0); Tick fires on each."),
    NodeDef("lfo", "LFO", NodeCategory.Time,
        inputs = listOf(num("Rate Hz", 0.2f, 0f, 20f), num("Min", 0f, -2000f, 2000f), num("Max", 1f, -2000f, 2000f), num("Phase", 0f, 0f, 1f)),
        outputs = listOf(sig("Value")), props = listOf(PropDef("Shape", LfoShape.entries.map { it.name })),
        help = "A low-frequency oscillator between Min and Max."),
    // Numbers only: there is no sound.
    NodeDef("fmam", "FM/AM", NodeCategory.Time,
        inputs = listOf(num("Carrier Hz", 1f, 0f, 20f), num("Modulator Hz", 0.25f, 0f, 20f), num("AM depth", 0.5f, 0f, 1f),
            num("FM index", 2f, 0f, 20f), num("Min", 0f, -2000f, 2000f), num("Max", 1f, -2000f, 2000f)),
        outputs = listOf(sig("Value"), sig("Carrier"), sig("Modulator")), props = listOf(PropDef("Mode", FmAmMode.entries.map { it.name })),
        help = "A carrier wave changed by a modulator wave, between Min and Max. AM: the modulator turns the carrier up and down " +
            "(AM depth). FM: it pushes the carrier faster and slower (FM index). Ring: the two multiplied."),
    NodeDef("sensor", "Sensor", NodeCategory.Time,
        inputs = listOf(num("Min", 0f, -2000f, 2000f), num("Max", 1f, -2000f, 2000f), num("Smoothing", 0.3f, 0f, 1f)),
        outputs = listOf(sig("Value"), sig("Raw"), sig("High-pass"), sig("Change"), sig("Seed")),
        props = listOf(PropDef("Source", SENSOR_SOURCES.map { it.label }), PropDef("Phone", listOf("This", "Other"))),
        help = "A phone sensor mapped to Min..Max (Raw is in the sensor's own unit). High-pass is Raw minus its slow average " +
            "(shake and motion), Change is Raw's rate of change per second, and Seed is a whole number mixed from the sensor's " +
            "readings: wire it into a seed to let the world corrupt the picture. Other = the linked phone."),
    NodeDef("hold", "Sample & Hold", NodeCategory.Time,
        inputs = listOf(num("Value", 0f, -2000f, 2000f), SocketDef("Trigger", SocketType.Trigger)), outputs = listOf(sig("Value")),
        help = "Keeps Value from the last time Trigger fired."),
    // Its output is a Value, not a Signal, so muting the node passes Value straight through.
    NodeDef("smooth", "Smooth", NodeCategory.Time,
        inputs = listOf(num("Value", 0f, -2000f, 2000f), num("Rise s", 0.3f, 0f, 30f), num("Fall s", 0.3f, 0f, 30f), SocketDef("Reset", SocketType.Trigger)),
        outputs = listOf(num("Value", 0f, 0f, 1f)),
        help = "Follows Value slowly. Rise s is the time to go most of the way up, Fall s the time to go down (0 = at once). Reset jumps to Value."),
    // Gates: held on/off signals, with the envelope that shapes a value from one
    NodeDef("gateswitch", "Gate Switch", NodeCategory.Time, outputs = listOf(gate("Gate")),
        props = listOf(PropDef("State", listOf("Off", "On"))), help = "A gate you switch by hand."),
    NodeDef("trig2gate", "Trigger to Gate", NodeCategory.Time,
        inputs = listOf(SocketDef("Trigger", SocketType.Trigger), num("Length s", 0.25f, 0f, 60f)), outputs = listOf(gate("Gate")),
        help = "Each Trigger opens the gate for Length seconds; a new Trigger restarts the length."),
    NodeDef("gate2trig", "Gate to Trigger", NodeCategory.Time,
        inputs = listOf(gate("Gate")), outputs = listOf(SocketDef("Trigger", SocketType.Trigger)),
        props = listOf(PropDef("Edge", listOf("Rising", "Falling"))), help = "A Trigger on the moment the gate opens (Rising) or closes (Falling)."),
    NodeDef("gatelogic", "Gate Logic", NodeCategory.Time,
        inputs = listOf(gate("A"), gate("B")), outputs = listOf(gate("Gate")),
        props = listOf(PropDef("Operation", listOf("AND", "OR", "XOR", "NOT A"))), help = "Combines two gates."),
    NodeDef("gatedelay", "Gate Delay", NodeCategory.Time,
        inputs = listOf(gate("Gate"), num("Delay s", 0.25f, 0f, 60f)), outputs = listOf(gate("Gate")),
        help = "The gate as it was Delay seconds ago."),
    NodeDef("gatedivide", "Gate Divide", NodeCategory.Time,
        inputs = listOf(gate("Gate"), num("Every", 2f, 1f, 64f, whole = true)), outputs = listOf(gate("Gate")),
        help = "Passes every Nth time the gate opens."),
    NodeDef("gateprob", "Gate Chance", NodeCategory.Time,
        inputs = listOf(gate("Gate"), num("Probability", 0.5f, 0f, 1f), num("Seed", 1f, 0f, 9999f, whole = true)), outputs = listOf(gate("Gate")),
        help = "Each time the gate opens, passes the whole open phase with this probability (the same Seed gives the same draws)."),
    NodeDef("envelope", "Envelope", NodeCategory.Time,
        inputs = listOf(gate("Gate"), num("Attack ms", 50f, 0f, 20000f), num("Decay ms", 200f, 0f, 20000f), num("Sustain", 0.6f, 0f, 1f), num("Release ms", 400f, 0f, 20000f)),
        outputs = listOf(sig("Level")), props = listOf(PropDef("Curve", dk.cbavf.studio.math.Curve.entries.map { it.name })),
        help = "Attack, decay, sustain and release: the level rises while the gate is on, settles to Sustain, and falls when it closes."),
    NodeDef("animate", "Animate Frames", NodeCategory.Time, outputs = listOf(frames(), num("Count", 0f, 0f, 1f)),
        help = "The Animate room's frames, live."),
    NodeDef("sequence", "Sequence Frames", NodeCategory.Time, outputs = listOf(frames(), num("Count", 0f, 0f, 1f)),
        help = "The Sequence room's timeline items."),
    NodeDef("frameat", "Frame at", NodeCategory.Time,
        inputs = listOf(frames(), num("Index", 0f, 0f, 9999f, whole = true)), outputs = listOf(img(), num("Count", 0f, 0f, 1f)),
        props = listOf(PropDef("Wrap", listOf("Loop", "PingPong", "Clamp"))),
        help = "One frame of a set, by index (0 = first). Wire a Clock Step into Index to play it."),

    // Colour
    NodeDef("develop", "Develop", NodeCategory.Color,
        inputs = listOf(img(), num("Exposure", 0f, -3f, 3f), num("Temperature", 0f, -1f, 1f), num("Tint", 0f, -1f, 1f),
            num("Black", 0f, 0f, 0.5f), num("White", 1f, 0.5f, 1f), num("Mid-tones", 1f, 0.3f, 3f)),
        outputs = listOf(img())),
    NodeDef("mix", "Mix", NodeCategory.Color,
        inputs = listOf(img("A"), img("B"), num("Factor", 1f, 0f, 1f)), outputs = listOf(img()),
        props = listOf(PropDef("Blend", MIX_BLEND_NAMES)), help = "B over A with a blend mode; B is fitted to A's size."),
    NodeDef("composestack", "Compose Stack", NodeCategory.Color,
        inputs = ComposeStack.INPUTS.indices.map { img("Layer ${it + 1}") }, outputs = listOf(img()),
        props = listOf(PropDef("Unlinked", listOf("Rack", "Hide"))),
        help = "The Compose room's layers as one picture, live. A linked Layer 1 to 4 feeds that live layer instead of the Rack " +
            "(no live layer yet: it goes on top). An unlinked one shows the Rack's input, or nothing with Hide."),
    NodeDef("chsplit", "Split Channel", NodeCategory.Color, inputs = listOf(img()), outputs = listOf(img("Plane")),
        props = listOf(PropDef("Space", listOf("RGBA", "CMYK", "Lab")), PropDef("Channel", listOf("1 · R C L", "2 · G M a", "3 · B Y b", "4 · A K"))),
        help = "One colour channel of the picture as a grey plane. RGBA: R, G, B, A. CMYK: C, M, Y, K. Lab: L, a, b (a channel the space does not have is black)."),
    NodeDef("chmix", "Channel Mix", NodeCategory.Color,
        inputs = listOf(img(), num("Gain 1", 1f, 0f, 2f), num("Gain 2", 1f, 0f, 2f), num("Gain 3", 1f, 0f, 2f), num("Gain 4", 1f, 0f, 2f),
            num("Offset 1", 0f, -0.5f, 0.5f), num("Offset 2", 0f, -0.5f, 0.5f), num("Offset 3", 0f, -0.5f, 0.5f), num("Offset 4", 0f, -0.5f, 0.5f)),
        outputs = listOf(img()), props = listOf(PropDef("Space", listOf("RGBA", "CMYK", "Lab"))),
        help = "A gain and an offset for each colour channel in the chosen space (RGBA: R G B A, CMYK: C M Y K, Lab: L a b). Gain 1 is unchanged, offset 0 is unchanged."),
    NodeDef("chmerge", "Merge Channels", NodeCategory.Color, inputs = listOf(img("Ch 1"), img("Ch 2"), img("Ch 3"), img("Ch 4")), outputs = listOf(img()),
        props = listOf(PropDef("Space", listOf("RGBA", "CMYK", "Lab"))),
        help = "Grey planes back into one picture in the chosen space (Ch 1 must be linked; a missing plane is zero, alpha opaque, Lab a and b neutral). Planes are fitted to Ch 1's size."),
    NodeDef("invert", "Invert", NodeCategory.Color, inputs = listOf(img()), outputs = listOf(img())),
    NodeDef("desaturate", "Desaturate", NodeCategory.Color, inputs = listOf(img()), outputs = listOf(img())),
    NodeDef("posterize", "Posterize", NodeCategory.Color, inputs = listOf(img(), num("Levels", 4f, 2f, 32f, whole = true)), outputs = listOf(img())),
    NodeDef("threshold", "Threshold", NodeCategory.Color, inputs = listOf(img(), num("Level", 0.5f, 0f, 1f)), outputs = listOf(img())),

    // Filters
    NodeDef("blur", "Blur", NodeCategory.Filter, inputs = listOf(img(), num("Radius", 8f, 0f, 200f)), outputs = listOf(img())),
    NodeDef("sharpen", "Sharpen", NodeCategory.Filter, inputs = listOf(img(), num("Amount", 1f, 0f, 5f), num("Radius", 2f, 1f, 50f)), outputs = listOf(img())),
    NodeDef("noise", "Noise", NodeCategory.Filter, inputs = listOf(img(), num("Amount", 0.1f, 0f, 1f), num("Seed", 1f, 0f, 9999f, whole = true)), outputs = listOf(img())),
    NodeDef("fft", "FFT Filter", NodeCategory.Filter, inputs = listOf(img(), num("Radius", 0.1f, 0.005f, 0.5f), num("Band", 0.05f, 0.005f, 0.3f)),
        outputs = listOf(img()), props = listOf(PropDef("Filter", listOf("LowPass", "HighPass", "BandPass")))),
    NodeDef("glitch", "Glitch", NodeCategory.Filter,
        inputs = listOf(img(), num("Width", 0f, -128f, 128f, whole = true), num("Stride", 0f, -512f, 512f, whole = true), num("Chroma X", 0f, -64f, 64f, whole = true), num("JPEG gens", 0f, 0f, 30f, whole = true)),
        outputs = listOf(img()), props = listOf(PropDef("Bytes/px", listOf("3", "4", "2", "1")))),
    NodeDef("fmamimage", "FM/AM Image", NodeCategory.Filter,
        inputs = listOf(img(), num("Carrier", 40f, 0f, 400f), num("Modulator", 4f, 0f, 100f), num("AM depth", 0.5f, 0f, 1f),
            num("FM index", 2f, 0f, 20f), num("Phase", 0f, -10f, 10f), num("Amount", 1f, 0f, 1f), num("Shift px", 20f, 0f, 400f)),
        outputs = listOf(img()),
        props = listOf(PropDef("Mode", FmAmMode.entries.map { it.name }), PropDef("Effect", listOf("Brightness", "Shift")), PropDef("Direction", listOf("Down", "Across"))),
        help = "The FM/AM wave laid over the picture, one value per line. Carrier and Modulator are waves across the picture. " +
            "Brightness darkens the low parts (by Amount); Shift slides each line by up to Shift px. Wire a Clock into Phase to move it."),

    // Layout
    NodeDef("reroute", "Reroute", NodeCategory.Layout, inputs = listOf(SocketDef("In", SocketType.Any)), outputs = listOf(SocketDef("Out", SocketType.Any)),
        help = "A dot to route links tidily; carries whatever flows in. Also: swipe links with the Add reroutes tool."),

    // Output
    NodeDef("viewer", "Viewer", NodeCategory.Output, inputs = listOf(img()), sink = true, help = "Shows its input on the backdrop."),
    NodeDef("save", "Save to Library", NodeCategory.Output, inputs = listOf(img()), sink = true, help = "Run renders full size and files it, with the graph as recipe."),
    NodeDef("tocompose", "Send to Compose", NodeCategory.Output, inputs = listOf(img()), sink = true, help = "Run saves it and adds it as a Compose layer."),
    NodeDef("tophone", "Send to Other Phone", NodeCategory.Output,
        inputs = listOf(img(), num("Max fps", 4f, 0.2f, 30f), num("Size", 1080f, 128f, 4096f, whole = true)),
        props = listOf(PropDef("Format", listOf("JPEG", "PNG"))), sink = true,
        help = "Sends its input to the phone linked in Loop: continuously while Live (up to Max fps), or once with Send. PNG keeps moiré exact."),
    NodeDef("projector", "Projector", NodeCategory.Output, inputs = listOf(img()), sink = true,
        help = "Full screen shows its input on this whole screen at full brightness, live while Live runs. Tap or Back to close."),
    NodeDef("record", "Record to Animate", NodeCategory.Output, inputs = listOf(canvas(), SocketDef("Trigger", SocketType.Trigger)), sink = true,
        help = "While Live and armed, adds the canvas as an Animate frame on each Trigger (unlinked: at Animate's fps)."),
)
