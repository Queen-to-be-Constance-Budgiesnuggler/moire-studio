package dk.cbavf.studio.nodes.model

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

/** Every node type routing-nodes knows. */
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

private fun standardNodes(): List<NodeDef> = listOf(
    // Input
    NodeDef("image", "Library Image", NodeCategory.Input, outputs = listOf(img()), props = listOf(PropDef("asset")),
        help = "A picture from the Library (tap Pick on the node)."),
    NodeDef("draw", "Draw Canvas", NodeCategory.Input, outputs = listOf(SocketDef("Canvas", SocketType.Canvas)),
        help = "The Draw room's canvas, live."),
    NodeDef("fromphone", "From Other Phone", NodeCategory.Input, outputs = listOf(img(), num("Count", 0f, 0f, 1f)),
        help = "The latest picture from the phone linked in Loop: its Send to Other Phone node, or a Loop capture. Count goes up with each."),
    NodeDef("value", "Value", NodeCategory.Input, inputs = listOf(num("Value", 1f, -1000f, 1000f)), outputs = listOf(num("Value", 0f, 0f, 1f))),

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
    NodeDef("sensor", "Sensor", NodeCategory.Time,
        inputs = listOf(num("Min", 0f, -2000f, 2000f), num("Max", 1f, -2000f, 2000f), num("Smoothing", 0.3f, 0f, 1f)),
        outputs = listOf(sig("Value"), sig("Raw")),
        props = listOf(PropDef("Source", SENSOR_SOURCES.map { it.label }), PropDef("Phone", listOf("This", "Other"))),
        help = "A phone sensor mapped to Min..Max (Raw is in the sensor's own unit). Other = the linked phone."),
    NodeDef("hold", "Sample & Hold", NodeCategory.Time,
        inputs = listOf(num("Value", 0f, -2000f, 2000f), SocketDef("Trigger", SocketType.Trigger)), outputs = listOf(sig("Value")),
        help = "Keeps Value from the last time Trigger fired."),
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
        props = listOf(PropDef("Blend", BLEND_NAMES)), help = "B over A with a blend mode; B is fitted to A's size."),
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
