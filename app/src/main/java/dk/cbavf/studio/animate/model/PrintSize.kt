package dk.cbavf.studio.animate.model

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Paper for the barrier print, portrait size in mm. [Screen] keeps the canvas size as it is. */
enum class Paper(val label: String, val widthMm: Double, val heightMm: Double) {
    Screen("Screen", 0.0, 0.0),
    A4("A4", 210.0, 297.0),
    A5("A5", 148.0, 210.0),
    Letter("Letter", 215.9, 279.4),
}

/**
 * How a barrier print is laid out: the whole sheet is [widthPx] × [heightPx]; the canvas is drawn
 * [scale] times larger, centred at [offsetX], [offsetY]; strips are [stripPx] wide on the sheet.
 * [dpi] is the one actually used, lower than asked for when [clamped].
 */
data class PrintPlan(
    val paper: Paper,
    val widthPx: Int,
    val heightPx: Int,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val offsetX: Int,
    val offsetY: Int,
    val scale: Float,
    val stripPx: Int,
    val dpi: Int,
    val clamped: Boolean,
) {
    val pixels: Long get() = widthPx.toLong() * heightPx

    /** Width of one strip on paper, in mm. */
    val stripMm: Double get() = PrintSize.pxToMm(stripPx, dpi)
}

object PrintSize {
    val DPIS = listOf(150, 300, 600)
    const val DEFAULT_DPI = 300

    /** Largest sheet in pixels; above it the dpi is lowered. A4 at 600 dpi is about 35 million. */
    const val MAX_PIXELS = 24_000_000L

    private const val MM_PER_INCH = 25.4

    fun mmToPx(mm: Double, dpi: Int): Int = (mm / MM_PER_INCH * dpi).roundToInt()

    fun pxToMm(px: Int, dpi: Int): Double = if (dpi <= 0) 0.0 else px * MM_PER_INCH / dpi

    /** The canvas as it is: no scaling, no margin. */
    fun screen(canvasW: Int, canvasH: Int, stripPx: Int, dpi: Int = DEFAULT_DPI) = PrintPlan(
        Paper.Screen, canvasW, canvasH, canvasW, canvasH, 0, 0, 1f, stripPx.coerceAtLeast(1), dpi, clamped = false,
    )

    /**
     * Fits a [canvasW] × [canvasH] canvas on [paper] at [dpi]. The sheet turns to landscape when
     * the canvas is wider than tall. The strip grows with the canvas, so the print looks like the screen.
     */
    fun plan(paper: Paper, dpi: Int, canvasW: Int, canvasH: Int, stripPx: Int, maxPixels: Long = MAX_PIXELS): PrintPlan {
        val w = canvasW.coerceAtLeast(1)
        val h = canvasH.coerceAtLeast(1)
        if (paper == Paper.Screen) return screen(w, h, stripPx, dpi)
        val landscape = w > h
        val wMm = if (landscape) paper.heightMm else paper.widthMm
        val hMm = if (landscape) paper.widthMm else paper.heightMm
        val used = fitDpi(wMm, hMm, dpi.coerceAtLeast(1), maxPixels)
        val sheetW = mmToPx(wMm, used).coerceAtLeast(1)
        val sheetH = mmToPx(hMm, used).coerceAtLeast(1)
        val scale = minOf(sheetW.toFloat() / w, sheetH.toFloat() / h)
        // Same rounding as renderBitmap, so the plan and the rendered frame agree.
        val imageW = (w * scale).toInt().coerceIn(1, sheetW)
        val imageH = (h * scale).toInt().coerceIn(1, sheetH)
        return PrintPlan(
            paper = paper,
            widthPx = sheetW,
            heightPx = sheetH,
            imageWidthPx = imageW,
            imageHeightPx = imageH,
            offsetX = (sheetW - imageW) / 2,
            offsetY = (sheetH - imageH) / 2,
            scale = scale,
            stripPx = (stripPx * scale).roundToInt().coerceAtLeast(1),
            dpi = used,
            clamped = used < dpi,
        )
    }

    /** One line for the Barrier panel: what will be saved. [askedDpi] is the dpi the user picked. */
    fun describe(plan: PrintPlan, askedDpi: Int): String {
        val size = "${plan.widthPx} × ${plan.heightPx} px"
        if (plan.paper == Paper.Screen) return "Screen size: $size, strip ${plan.stripPx} px."
        val mm = String.format(java.util.Locale.US, "%.2f", plan.stripMm)
        val tooBig = if (plan.clamped) " $askedDpi dpi is too big for one image." else ""
        return "${plan.paper.label} at ${plan.dpi} dpi: $size, strip ${plan.stripPx} px = $mm mm.$tooBig"
    }

    /** Shown when saving runs out of memory. Screen has no dpi to lower, so no advice there. */
    fun tooBigMessage(paper: Paper): String =
        if (paper == Paper.Screen) "Too big to save." else "Too big to save. Pick a lower dpi."

    /** The highest dpi up to [dpi] whose sheet stays within [maxPixels]. */
    fun fitDpi(wMm: Double, hMm: Double, dpi: Int, maxPixels: Long): Int {
        fun count(d: Int) = mmToPx(wMm, d).toLong() * mmToPx(hMm, d)
        if (count(dpi) <= maxPixels) return dpi
        var d = floor(dpi * sqrt(maxPixels.toDouble() / count(dpi))).toInt().coerceAtLeast(1)
        // Rounding to whole pixels can land one off either way; step to the best fit.
        while (d > 1 && count(d) > maxPixels) d--
        while (d < dpi && count(d + 1) <= maxPixels) d++
        return d
    }
}
