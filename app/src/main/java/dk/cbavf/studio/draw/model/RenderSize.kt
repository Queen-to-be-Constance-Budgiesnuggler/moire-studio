package dk.cbavf.studio.draw.model

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The size a Draw canvas is saved at. [Screen] is screen-exact: one saved pixel per screen pixel, so the moiré in the file is
 * the moiré on screen. The others draw the same picture with more pixels: [scale] times the canvas, or with the long side
 * [longSidePx] wide. Kept with the canvas, so one piece keeps one render mode.
 */
enum class RenderSize(val label: String, val scale: Int = 0, val longSidePx: Int = 0) {
    Screen("Screen", scale = 1),
    X2("2×", scale = 2),
    X3("3×", scale = 3),
    X4("4×", scale = 4),
    Long4096("4096 px", longSidePx = 4096),
    Long6000("6000 px", longSidePx = 6000),
}

/** What a Draw save will be: [widthPx] × [heightPx], the canvas drawn [scale] times larger. [clamped] when smaller than asked. */
data class RenderPlan(
    val size: RenderSize,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
    val clamped: Boolean,
) {
    val pixels: Long get() = widthPx.toLong() * heightPx
}

object RenderSizes {
    /** Largest image in pixels, shared by every full-size bitmap held at once. The same limit as Animate's print export. */
    const val MAX_PIXELS = 24_000_000L

    /** Full-size bitmaps a save holds at once: a subtracting layer is painted on its own and taken from the picture so far. */
    fun bitmapsFor(subtracts: Boolean): Int = if (subtracts) 3 else 1

    /** The scale [size] asks for on a [canvasW] × [canvasH] canvas, before any limit. */
    fun askedScale(size: RenderSize, canvasW: Int, canvasH: Int): Float = when {
        // Half a pixel more, so float rounding never leaves the long side one pixel short.
        size.longSidePx > 0 -> (size.longSidePx + 0.5f) / max(canvasW, canvasH).coerceAtLeast(1)
        else -> size.scale.coerceAtLeast(1).toFloat()
    }

    /**
     * Sizes a save of a [canvasW] × [canvasH] canvas at [size]. [bitmaps] full-size pictures are held at once (a subtracting
     * layer needs three), and together they stay within [maxPixels]; above it the scale is lowered. [RenderSize.Screen] is
     * never lowered: it is the canvas as it is.
     */
    fun plan(size: RenderSize, canvasW: Int, canvasH: Int, bitmaps: Int = 1, maxPixels: Long = MAX_PIXELS): RenderPlan {
        val w = canvasW.coerceAtLeast(1)
        val h = canvasH.coerceAtLeast(1)
        if (size == RenderSize.Screen) return RenderPlan(size, w, h, 1f, clamped = false)
        val asked = askedScale(size, w, h)
        val limit = maxPixels / bitmaps.coerceAtLeast(1)
        var scale = asked
        if (count(w, h, scale) > limit) {
            scale = sqrt(limit.toDouble() / (w.toDouble() * h)).toFloat().coerceAtMost(asked)
            // Rounding to whole pixels can land just over; step down until it fits.
            while (scale > MIN_SCALE && count(w, h, scale) > limit) scale *= 0.999f
        }
        return RenderPlan(size, px(w, scale), px(h, scale), scale, clamped = scale < asked)
    }

    /** One line for the size menu: what will be saved. */
    fun describe(plan: RenderPlan): String {
        val px = "${plan.widthPx} × ${plan.heightPx} px"
        if (plan.size == RenderSize.Screen) return "Screen size: $px."
        val tooBig = if (plan.clamped) " ${plan.size.label} is too big for one image." else ""
        return "${plan.size.label}: $px.$tooBig"
    }

    /** Added to the "Saved" message: the size, when it is not the screen's. */
    fun savedNote(plan: RenderPlan): String =
        if (plan.size == RenderSize.Screen) "" else ", ${plan.widthPx} × ${plan.heightPx} px"

    /** Shown when saving runs out of memory. Screen has no smaller size to offer. */
    fun tooBigMessage(size: RenderSize): String =
        if (size == RenderSize.Screen) "Too big to save." else "Too big to save. Pick a smaller size."

    private const val MIN_SCALE = 0.01f

    /** Same rounding as renderBitmap, so the plan and the saved image agree. */
    private fun px(side: Int, scale: Float) = (side * scale).toInt().coerceAtLeast(1)

    private fun count(w: Int, h: Int, scale: Float) = px(w, scale).toLong() * px(h, scale)
}
