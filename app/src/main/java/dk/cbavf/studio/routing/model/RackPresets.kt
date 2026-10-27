package dk.cbavf.studio.routing.model

import kotlinx.serialization.Serializable

/** A rack preset: a whole cable layout saved by name (docs/ARCHITECTURE.md §13). */
@Serializable
data class RackPreset(val name: String, val cables: List<Cable>, val at: Long = 0L)

/** What loading a preset gives: the new patching, and the cables that no longer fit the rack (left out). */
data class RackLoad(val doc: RoutingDoc, val dropped: List<Cable>)

/**
 * Rack presets: the ones you save (kept with the project) and the built-in ones, which are always there and cannot be deleted.
 * A preset replaces the whole patching. Its cables are patched again one by one with the usual rules, so a cable whose jack has
 * gone, or that no longer fits, is left out rather than breaking the rack.
 */
object RackPresets {
    val BUILT_IN: List<RackPreset> = listOf(
        RackPreset("Empty rack", emptyList()),
        RackPreset("Draw into Compose", listOf(Cable("draw.canvas", "compose.in1"), Cable("animate.frame", "compose.in2"))),
        RackPreset("Camera chain", listOf(
            Cable("camera.last", "develop.in"), Cable("develop.image", "glitch.in"), Cable("glitch.image", SEQUENCE_IN),
        )),
        RackPreset("Sensors into Node Lab", listOf(
            Cable("sensors.roll", "nodes.sig1"), Cable("sensors.pitch", "nodes.sig2"), Cable("sensors.seed", "glitch.seed"),
        )),
        RackPreset("Touch pad into Node Lab", listOf(Cable(PATCH_TOUCH_X, "nodes.sig1"), Cable(PATCH_TOUCH_Y, "nodes.sig2"))),
        RackPreset("Patch bay to OSC out", listOf(
            Cable(PATCH_LFO1, "oscout.a"), Cable(PATCH_LFO2, "oscout.b"), Cable(PATCH_TOUCH_X, "oscout.c"), Cable(PATCH_TOUCH_Y, "oscout.d"),
        )),
    )

    fun isBuiltIn(p: RackPreset) = BUILT_IN.any { it.name == p.name && it.cables == p.cables }

    /** [doc]'s cables saved as [name]: replaces a saved preset of the same name, keeps the rest in order. Blank names are refused. */
    fun save(saved: List<RackPreset>, name: String, doc: RoutingDoc, at: Long): List<RackPreset> {
        val n = name.trim()
        if (n.isEmpty()) return saved
        return saved.filterNot { it.name == n } + RackPreset(n, doc.cables, at)
    }

    fun delete(saved: List<RackPreset>, name: String): List<RackPreset> = saved.filterNot { it.name == name }

    /** The patching [p] makes on [rack], built cable by cable; what did not fit is listed in [RackLoad.dropped]. */
    fun load(p: RackPreset, rack: Rack = Rack): RackLoad {
        var doc = RoutingDoc()
        val dropped = ArrayList<Cable>()
        for (c in p.cables) {
            // An input takes one cable: a second cable into the same input in the preset is dropped, not swapped in.
            val next = if (doc.sourceOf(c.to) != null) doc else doc.connect(c.from, c.to, rack)
            if (next == doc) dropped += c else doc = next
        }
        return RackLoad(doc, dropped)
    }

    /** Is the rack patched exactly as [p] (cables in any order)? */
    fun matches(p: RackPreset, doc: RoutingDoc): Boolean = p.cables.toSet() == doc.cables.toSet() && p.cables.size == doc.cables.size
}
