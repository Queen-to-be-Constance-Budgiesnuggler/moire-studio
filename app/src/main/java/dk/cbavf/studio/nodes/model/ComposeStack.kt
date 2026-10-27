package dk.cbavf.studio.nodes.model

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.model.ComposeLayer
import dk.cbavf.studio.compose.model.RouteLayerSource
import dk.cbavf.studio.routing.model.COMPOSE_INPUTS

/**
 * The Compose Stack node: the Compose room's layer stack as one image. Its inputs Layer 1 to 4
 * stand for Compose's live-layer inputs, so a linked input feeds that live layer instead of the
 * Rack. This part only plans the document; the platform draws it.
 */
object ComposeStack {
    /** A live layer fed by the node reads this input name, then the identity of what is linked. */
    const val NODE_INPUT = "nodes.stack:"

    /** The node's image inputs, in order: one per Compose live-layer input. */
    val INPUTS: List<String> = COMPOSE_INPUTS

    /** What a linked input carries: a stable identity and the image's full size. */
    data class Linked(val tag: String, val widthPx: Int, val heightPx: Int) {
        val input: String get() = NODE_INPUT + tag
    }

    /** A short, stable tag for an input's identity (the evaluator's key of what is linked). */
    fun tag(key: String): String = Integer.toHexString(key.hashCode()) + "." + key.length

    /**
     * [doc] as the node draws it. A live layer whose input has a [linked] image shows that image
     * (same place, same width). With [hideUnlinked], live layers with nothing linked are hidden;
     * otherwise they keep the Rack's input. A linked input with no live layer in [doc] is added
     * on top, fitted (an empty document takes the first one's size). Null when nothing shows.
     */
    fun plan(doc: ComposeDoc, linked: List<Linked?>, hideUnlinked: Boolean): ComposeDoc? {
        val used = HashSet<Int>()
        val layers = doc.layers.map { l ->
            val src = l.source as? RouteLayerSource ?: return@map l
            val i = INPUTS.indexOf(src.input)
            val v = if (i >= 0) linked.getOrNull(i) else null
            when {
                v != null -> { used += i; retarget(l, v) }
                hideUnlinked -> l.copy(visible = false)
                else -> l
            }
        }
        val extra = linked.mapIndexedNotNull { i, v -> if (v != null && i !in used) i to v else null }
        val first = extra.firstOrNull()?.second
        val base = if (doc.layers.isEmpty() && first != null && first.widthPx > 0 && first.heightPx > 0) {
            doc.copy(widthPx = first.widthPx, heightPx = first.heightPx)
        } else doc
        var id = base.nextId
        val added = extra.map { (i, v) ->
            ComposeDoc.fitted(id++, "Node · Layer ${i + 1}", RouteLayerSource(v.input, v.widthPx, v.heightPx),
                v.widthPx, v.heightPx, base.widthPx, base.heightPx)
        }
        val out = base.copy(layers = layers + added)
        return out.takeIf { o -> o.layers.any { !it.isGroup && o.shown(it) } }
    }

    /** [l] showing [v] instead: the same centre and rotation, scaled to keep the layer's width. */
    fun retarget(l: ComposeLayer, v: Linked): ComposeLayer {
        val w = v.widthPx.coerceAtLeast(1)
        val h = v.heightPx.coerceAtLeast(1)
        val k = l.sourceWidth.toFloat() / w
        return l.copy(
            source = RouteLayerSource(v.input, w, h),
            transform = l.transform.copy(sx = l.transform.sx * k, sy = l.transform.sy * k),
        )
    }
}
