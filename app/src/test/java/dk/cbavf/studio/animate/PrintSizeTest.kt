package dk.cbavf.studio.animate

import dk.cbavf.studio.animate.model.Paper
import dk.cbavf.studio.animate.model.PrintSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintSizeTest {
    @Test
    fun mmToPxUsesInches() {
        assertEquals(300, PrintSize.mmToPx(25.4, 300))
        assertEquals(2480, PrintSize.mmToPx(210.0, 300))
        assertEquals(3508, PrintSize.mmToPx(297.0, 300))
    }

    @Test
    fun a4At300DpiIsTheUsualSheet() {
        val p = PrintSize.plan(Paper.A4, 300, 1000, 1400, 3)
        assertEquals(2480, p.widthPx)
        assertEquals(3508, p.heightPx)
        assertEquals(300, p.dpi)
        assertFalse(p.clamped)
    }

    @Test
    fun a5At150Dpi() {
        val p = PrintSize.plan(Paper.A5, 150, 1000, 1400, 3)
        assertEquals(874, p.widthPx)
        assertEquals(1240, p.heightPx)
    }

    @Test
    fun letterAt300DpiIsEightAndAHalfByEleven() {
        val p = PrintSize.plan(Paper.Letter, 300, 1000, 1400, 3)
        assertEquals(2550, p.widthPx)
        assertEquals(3300, p.heightPx)
    }

    @Test
    fun doublingTheDpiDoublesThePixels() {
        val low = PrintSize.plan(Paper.A5, 150, 1000, 1400, 3)
        val high = PrintSize.plan(Paper.A5, 300, 1000, 1400, 3)
        assertEquals(low.widthPx * 2, high.widthPx)
        assertEquals(low.heightPx * 2, high.heightPx)
        assertEquals(low.scale * 2, high.scale, 0.01f)
    }

    @Test
    fun wideCanvasTurnsTheSheetToLandscape() {
        val p = PrintSize.plan(Paper.A4, 300, 2000, 1000, 3)
        assertEquals(3508, p.widthPx)
        assertEquals(2480, p.heightPx)
    }

    @Test
    fun canvasFitsInsideTheSheetAndIsCentred() {
        val p = PrintSize.plan(Paper.A4, 300, 1080, 2340, 3)
        assertTrue(p.imageWidthPx <= p.widthPx)
        assertTrue(p.imageHeightPx <= p.heightPx)
        // Tall canvas: height is the tight side, the margin is left and right.
        assertTrue(p.imageHeightPx >= p.heightPx - 1)
        assertEquals((p.widthPx - p.imageWidthPx) / 2, p.offsetX)
        assertTrue(p.offsetX > 0)
    }

    @Test
    fun stripGrowsWithTheCanvas() {
        val p = PrintSize.plan(Paper.A4, 300, 1240, 1754, 3)
        // The canvas is drawn twice as large, so a 3 px strip becomes 6 px.
        assertEquals(2f, p.scale, 0.01f)
        assertEquals(6, p.stripPx)
    }

    @Test
    fun stripIsAtLeastOnePixel() {
        val p = PrintSize.plan(Paper.A5, 150, 10_000, 14_000, 1)
        assertEquals(1, p.stripPx)
    }

    @Test
    fun a4At600DpiIsClampedToTheBitmapLimit() {
        val p = PrintSize.plan(Paper.A4, 600, 1080, 2340, 3)
        assertTrue(p.clamped)
        assertTrue(p.dpi < 600)
        assertTrue(p.pixels <= PrintSize.MAX_PIXELS)
    }

    @Test
    fun clampKeepsTheHighestDpiThatFits() {
        val max = 1_000_000L
        val d = PrintSize.fitDpi(210.0, 297.0, 600, max)
        val count = { dpi: Int -> PrintSize.mmToPx(210.0, dpi).toLong() * PrintSize.mmToPx(297.0, dpi) }
        assertTrue(count(d) <= max)
        assertTrue(count(d + 1) > max)
    }

    @Test
    fun smallSheetIsNotClamped() {
        assertEquals(150, PrintSize.fitDpi(148.0, 210.0, 150, PrintSize.MAX_PIXELS))
    }

    @Test
    fun screenKeepsTheCanvasSize() {
        val p = PrintSize.plan(Paper.Screen, 600, 1080, 2340, 3)
        assertEquals(1080, p.widthPx)
        assertEquals(2340, p.heightPx)
        assertEquals(1f, p.scale, 0f)
        assertEquals(3, p.stripPx)
        assertEquals(0, p.offsetX)
        assertFalse(p.clamped)
    }

    @Test
    fun describeSaysWhenTheDpiWasLowered() {
        val p = PrintSize.plan(Paper.A4, 600, 1080, 2340, 3)
        val text = PrintSize.describe(p, 600)
        assertTrue(text.startsWith("A4 at ${p.dpi} dpi"))
        assertTrue(text.contains("600 dpi is too big"))
    }

    @Test
    fun tooBigOnScreenGivesNoDpiAdvice() {
        assertEquals("Too big to save.", PrintSize.tooBigMessage(Paper.Screen))
    }

    @Test
    fun tooBigOnPaperSuggestsALowerDpi() {
        assertTrue(PrintSize.tooBigMessage(Paper.A4).contains("lower dpi"))
    }

    @Test
    fun stripMmFollowsTheDpi() {
        val p = PrintSize.plan(Paper.A4, 300, 1240, 1754, 3)
        // 6 px at 300 dpi is 0.508 mm.
        assertEquals(0.508, p.stripMm, 0.001)
    }
}
