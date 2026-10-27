package dk.cbavf.studio.routing.model

import dk.cbavf.studio.draw.model.ImageLayer
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.library.CanvasDoc

/**
 * Draw's image-layer input (§13): an ordinary image layer whose asset id names a rack input
 * ("route:draw.in") instead of a Library image. Its pixels are whatever the cable carries; on
 * Save the picture is filed in the Library first and the saved canvas names that file instead.
 */
object RouteLayers {
    const val PREFIX = "route:"

    fun idOf(input: String) = PREFIX + input

    /** The rack input a layer's asset id stands for, or null for a Library image. */
    fun inputOf(assetId: String): String? = if (assetId.startsWith(PREFIX)) assetId.removePrefix(PREFIX).ifEmpty { null } else null

    fun isRoute(assetId: String) = inputOf(assetId) != null

    /** Rack inputs the canvas shows as live layers, in layer order. */
    fun inputsIn(doc: CanvasDoc): List<String> =
        doc.layers.mapNotNull { (it.content as? ImageLayer)?.assetId?.let(::inputOf) }.distinct()

    /** True when [input] is patched and the canvas has no live layer for it yet. */
    fun needsLayer(layers: List<Layer>, input: String, patched: Boolean): Boolean =
        patched && layers.none { (it.content as? ImageLayer)?.assetId == idOf(input) }

    /** A live layer for [input], a picture of [w]×[h], centred and fitted on the canvas. */
    fun layer(input: String, w: Int, h: Int, canvasW: Int, canvasH: Int): ImageLayer =
        ImageLayer.fitted(idOf(input), w, h, canvasW, canvasH)

    /**
     * The canvas with each live layer pointing at the Library file [snapshots] holds for its
     * input; live layers with no snapshot (nothing patched) are left out.
     */
    fun pinned(doc: CanvasDoc, snapshots: Map<String, String>): CanvasDoc {
        val layers = doc.layers.mapNotNull { layer ->
            val c = layer.content as? ImageLayer ?: return@mapNotNull layer
            val input = inputOf(c.assetId) ?: return@mapNotNull layer
            snapshots[input]?.let { layer.copy(content = c.copy(assetId = it)) }
        }
        return doc.copy(layers = layers)
    }
}
