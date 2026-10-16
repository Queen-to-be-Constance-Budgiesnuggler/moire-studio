package dk.cbavf.studio.nodes.model

import dk.cbavf.studio.patch.model.Curve
import dk.cbavf.studio.patch.model.PatchDoc
import dk.cbavf.studio.patch.model.SourceKind

/**
 * The Patch bay's routes as a routing-nodes graph: Draw Canvas → one Drive Layer per route →
 * Render → Viewer, each Drive fed by a Sensor or LFO node with the route's range and smoothing.
 * [layerIds] are the Draw layers bottom to top (Drive Layer counts from 1 = bottom); routes to a
 * layer that no longer exists are left out. Touch pad routes become a Value at the middle of their
 * range, and non-linear curves are noted on the node (nodes map linearly).
 */
fun patchToGraph(doc: PatchDoc, layerIds: List<Int>, widthPx: Int, heightPx: Int): Graph {
    val nodes = mutableListOf<Node>()
    val links = mutableListOf<Link>()
    var next = 1
    fun add(type: String, x: Float, y: Float, props: Map<String, Prop> = emptyMap(), muted: Boolean = false, label: String? = null) =
        Node(next++, type, x, y, props, muted = muted, label = label).also { nodes += it }.id

    val draw = add("draw", 0f, 0f)
    var last = draw
    var x = 260f
    for (r in doc.routes) {
        val layer = layerIds.indexOf(r.layerId)
        if (layer < 0) continue
        val curveNote = if (r.curve != Curve.Linear) " (was ${r.curve.name})" else ""
        val range = mapOf("Min" to Prop.Num(r.min), "Max" to Prop.Num(r.max))
        val source = when {
            r.source.isLfo -> {
                val lfo = if (r.source == SourceKind.Lfo1) doc.lfo1 else doc.lfo2
                add("lfo", x, 220f, range + mapOf("Rate Hz" to Prop.Num(lfo.rateHz), "Shape" to Prop.Choice(lfo.shape.name)), label = r.source.label + curveNote)
            }
            r.source.isTouch -> add("value", x, 220f, mapOf("Value" to Prop.Num((r.min + r.max) / 2)), label = "${r.source.label} (no touch pad yet)")
            else -> add(
                "sensor", x, 220f,
                range + mapOf(
                    "Source" to Prop.Choice(r.source.label),
                    "Phone" to Prop.Choice(if (r.remote) "Other" else "This"),
                    "Smoothing" to Prop.Num(r.smoothing),
                ),
                label = if (curveNote.isEmpty()) null else "Sensor$curveNote",
            )
        }
        val drive = add(
            "drive", x, 0f,
            mapOf("Parameter" to Prop.Choice(r.param.label), "Layer" to Prop.Num((layer + 1).toFloat())),
            muted = !r.enabled,
        )
        links += Link(last, 0, drive, 0)
        links += Link(source, 0, drive, 2)
        last = drive
        x += 260f
    }
    val render = add("render", x, 0f, mapOf("Paper" to Prop.Choice("White"), "Width" to Prop.Num(widthPx.toFloat()), "Height" to Prop.Num(heightPx.toFloat())))
    val viewer = add("viewer", x + 260f, 0f)
    links += Link(last, 0, render, 0)
    links += Link(render, 0, viewer, 0)
    return Graph(nodes, links)
}
