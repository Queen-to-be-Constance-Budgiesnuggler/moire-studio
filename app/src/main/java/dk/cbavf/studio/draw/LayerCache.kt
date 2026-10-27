package dk.cbavf.studio.draw

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import dk.cbavf.studio.blend.isSubtract
import dk.cbavf.studio.draw.model.Layer

/**
 * Finished layers of the Draw canvas kept as pictures between frames, so a canvas full of strokes is not painted again each
 * time a new stroke, the selection or the fringes move. [LayerCachePlanner] decides what to paint again; this does it.
 * Keep one per canvas (remember it); the pictures go when it does.
 */
class LayerCache {
    private val planner = LayerCachePlanner<Layer> { it.blend.isSubtract }
    private var full: ImageBitmap? = null
    private var base: ImageBitmap? = null

    fun draw(scope: DrawScope, layers: List<Layer>) = with(scope) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@with drawLayers(layers)
        // Reading the image version here also makes the canvas redraw when an image arrives.
        val context = Triple(w, h, LayerImages.version.intValue)
        val plan = planner.plan(layers, context)
        val f = full
        val b = base
        when (plan) {
            CachePlan.Live -> drawLayers(layers)
            CachePlan.Full -> if (f != null) drawImage(f) else fallBack(layers)
            is CachePlan.ExtendFull -> if (f != null) {
                paint(f) { drawLayers(layers.subList(plan.from, layers.size), sheet = false) }
                drawImage(f)
            } else {
                fallBack(layers)
            }
            is CachePlan.RebuildFull -> {
                if (plan.from > 0 && b == null) return@with fallBack(layers)
                val target = blank(f, w, h).also { full = it }
                paint(target) {
                    if (b != null && plan.from > 0) {
                        drawImage(b)
                        drawLayers(layers.subList(plan.from, layers.size), sheet = false)
                    } else {
                        drawLayers(layers)
                    }
                }
                drawImage(target)
            }
            is CachePlan.Base -> {
                if (plan.from > 0 && b == null) return@with fallBack(layers)
                val target = if (b != null && plan.from > 0) b else blank(b, w, h).also { base = it }
                if (plan.from == 0) {
                    paint(target) { drawLayers(layers.subList(0, plan.upTo)) }
                } else if (plan.from < plan.upTo) {
                    paint(target) { drawLayers(layers.subList(plan.from, plan.upTo), sheet = false) }
                }
                drawImage(target)
                drawLayers(layers.subList(plan.upTo, layers.size), sheet = false)
            }
        }
    }

    /** The pictures and the plan disagree (should not happen): draw directly and start again. */
    private fun DrawScope.fallBack(layers: List<Layer>) {
        planner.reset()
        drawLayers(layers)
    }

    /** [old] cleared if it has the right size, else a new bitmap. */
    private fun DrawScope.blank(old: ImageBitmap?, w: Int, h: Int): ImageBitmap {
        if (old == null || old.width != w || old.height != h) return ImageBitmap(w, h)
        paint(old) { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
        return old
    }

    private fun DrawScope.paint(target: ImageBitmap, block: DrawScope.() -> Unit) =
        CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(target), Size(target.width.toFloat(), target.height.toFloat()), block)
}

/** [drawLayers] through [cache]: only what changed since the last frame is painted again. */
fun DrawScope.drawLayersCached(layers: List<Layer>, cache: LayerCache) = cache.draw(this, layers)
