package dk.cbavf.studio.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneBrushTest {

    // --- plan -------------------------------------------------------------------------------

    @Test
    fun planReturnsDabWithCorrectPositions() {
        val dab = CloneBrush.plan(50f, 50f, 100f, 100f, 10f, 200, 200)
        assertNotNull(dab)
        dab!!
        assertTrue(dab.srcX <= 50)
        assertTrue(dab.srcY <= 50)
        assertTrue(dab.dstX <= 100)
        assertTrue(dab.dstY <= 100)
        assertTrue(dab.width > 0)
        assertEquals(dab.dstX - dab.srcX, 50)
        assertEquals(dab.dstY - dab.srcY, 50)
    }

    @Test
    fun planMaskHasSoftCircularFalloff() {
        val dab = CloneBrush.plan(50f, 50f, 100f, 100f, 10f, 200, 200)!!
        val maskW = dab.width
        val maskH = dab.mask.size / maskW
        // Centre of the mask should have a high value.
        val cx = maskW / 2
        val cy = maskH / 2
        val centre = dab.mask[cy * maskW + cx]
        // Edge of the mask (at the radius) should be close to zero.
        val edge = dab.mask[0]
        assertTrue("centre ($centre) should be > edge ($edge)", centre > edge)
        assertTrue("centre should be close to 1", centre > 0.8f)
    }

    @Test
    fun planClampsToImageBounds() {
        // Source near the left edge.
        val dab = CloneBrush.plan(2f, 50f, 50f, 50f, 10f, 100, 100)
        assertNotNull(dab)
        dab!!
        assertTrue("srcX should be >= 0", dab.srcX >= 0)
        assertTrue("dstX should be >= 0", dab.dstX >= 0)
    }

    @Test
    fun planReturnsNullForZeroRadius() {
        assertNull(CloneBrush.plan(50f, 50f, 100f, 100f, 0f, 200, 200))
    }

    @Test
    fun planReturnsNullWhenEntirelyOutOfBounds() {
        // Both source and destination far outside the image.
        assertNull(CloneBrush.plan(-100f, -100f, -50f, -50f, 5f, 100, 100))
    }

    @Test
    fun planHandlesSourceEqualToDestination() {
        val dab = CloneBrush.plan(50f, 50f, 50f, 50f, 8f, 100, 100)
        assertNotNull(dab)
        dab!!
        assertEquals(dab.srcX, dab.dstX)
        assertEquals(dab.srcY, dab.dstY)
    }

    // --- applyClone -------------------------------------------------------------------------

    @Test
    fun applyCloneCopiesSourcePixelsToDestination() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { 0xFF000000.toInt() } // all black
        // Paint a red square in the source area.
        for (y in 20..30) for (x in 20..30) pixels[y * w + x] = 0xFFFF0000.toInt()

        val dab = CloneBrush.plan(25f, 25f, 60f, 60f, 5f, w, h)!!
        CloneBrush.applyClone(pixels, w, h, dab)

        // The destination area should have some red pixels.
        val dstPixel = pixels[60 * w + 60]
        val r = (dstPixel shr 16) and 0xFF
        assertTrue("destination should have red from source, got R=$r", r > 100)
    }

    @Test
    fun applyCloneRespectsTheMaskFalloff() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        // White source area.
        for (y in 10..40) for (x in 10..40) pixels[y * w + x] = 0xFFFFFFFF.toInt()

        val dab = CloneBrush.plan(25f, 25f, 70f, 70f, 8f, w, h)!!
        CloneBrush.applyClone(pixels, w, h, dab)

        // Centre of destination should be brighter than the edge.
        val centre = pixels[70 * w + 70] and 0xFF
        // A pixel at the edge of the dab radius should be darker (more blended with the black dst).
        val edgeY = 70 + 7
        val edgePixel = pixels[edgeY * w + 70] and 0xFF
        assertTrue("centre ($centre) >= edge ($edgePixel)", centre >= edgePixel)
    }

    // --- applyHeal --------------------------------------------------------------------------

    @Test
    fun applyHealKeepsDestinationAverageColour() {
        val w = 100
        val h = 100
        // Source: textured red area (alternating red shades).
        val pixels = IntArray(w * h) { 0xFF808080.toInt() } // grey background
        for (y in 10..40) for (x in 10..40) {
            pixels[y * w + x] = if ((x + y) % 2 == 0) 0xFFFF0000.toInt() else 0xFFCC0000.toInt()
        }
        // Destination: blue area.
        for (y in 55..85) for (x in 55..85) pixels[y * w + x] = 0xFF0000FF.toInt()

        // Take a snapshot of the destination average before healing.
        val dstAvgBefore = averageRgb(pixels, w, 60, 60, 10, 10)

        val dab = CloneBrush.plan(25f, 25f, 70f, 70f, 6f, w, h)!!
        CloneBrush.applyHeal(pixels, w, h, dab)

        // The destination should be closer to its original average colour than to the source's red.
        val dstAfter = averageRgb(pixels, w, 66, 66, 4, 4)
        // Blue channel should still be dominant (the source was red, but heal shifts to match dst).
        assertTrue(
            "healed blue (${dstAfter[2]}) should be > healed red (${dstAfter[0]})",
            dstAfter[2] > dstAfter[0],
        )
    }

    @Test
    fun applyHealPreservesSourceTexture() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { 0xFF808080.toInt() }
        // Source: checkerboard texture (high frequency).
        for (y in 10..40) for (x in 10..40) {
            pixels[y * w + x] = if ((x + y) % 2 == 0) 0xFFA0A0A0.toInt() else 0xFF606060.toInt()
        }
        // Destination: flat colour.
        for (y in 55..85) for (x in 55..85) pixels[y * w + x] = 0xFF404040.toInt()

        val dab = CloneBrush.plan(25f, 25f, 70f, 70f, 6f, w, h)!!
        CloneBrush.applyHeal(pixels, w, h, dab)

        // After healing, the destination should have some variation (texture from source).
        val values = mutableListOf<Int>()
        for (dy in -3..3) for (dx in -3..3) {
            val px = pixels[(70 + dy) * w + (70 + dx)]
            values.add(px and 0xFF) // blue channel
        }
        val variance = values.map { it.toFloat() }.let { vs ->
            val mean = vs.average().toFloat()
            vs.map { (it - mean) * (it - mean) }.average()
        }
        // A flat destination would have zero variance; the healed region should have some.
        assertTrue("healed area should have texture variation, variance=$variance", variance > 1.0)
    }

    // --- transparent pixels -------------------------------------------------------------------

    @Test
    fun cloneFromTransparentDoesNotDarken() {
        val w = 100
        val h = 100
        // Destination is opaque white; source area is fully transparent.
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 10..40) for (x in 10..40) pixels[y * w + x] = 0x00000000

        val dab = CloneBrush.plan(25f, 25f, 70f, 70f, 6f, w, h)!!
        CloneBrush.applyClone(pixels, w, h, dab)

        // The destination should not have dark fringes: RGB should stay bright where alpha is high.
        val px = pixels[70 * w + 70]
        val a = px ushr 24
        val r = (px shr 16) and 0xFF
        // If alpha dropped but RGB stayed 0 (the old bug), R would be 0.
        // With correct premultiplied blending, R should track the white destination.
        if (a > 128) {
            assertTrue("white destination should stay bright, got R=$r A=$a", r > 128)
        }
    }

    @Test
    fun healFromTransparentDoesNotDarken() {
        val w = 100
        val h = 100
        // Destination is opaque blue; source area is fully transparent.
        val pixels = IntArray(w * h) { 0xFF0000FF.toInt() }
        for (y in 10..40) for (x in 10..40) pixels[y * w + x] = 0x00000000

        val dab = CloneBrush.plan(25f, 25f, 70f, 70f, 6f, w, h)!!
        CloneBrush.applyHeal(pixels, w, h, dab)

        val px = pixels[70 * w + 70]
        val a = px ushr 24
        val b = px and 0xFF
        // Blue destination should stay visible, not darken to black.
        if (a > 128) {
            assertTrue("blue destination should stay bright, got B=$b A=$a", b > 64)
        }
    }

    @Test
    fun cloneUsesSnapshotToAvoidReadingOwnOutput() {
        val w = 50
        val h = 50
        // Solid red image.
        val pixels = IntArray(w * h) { 0xFFFF0000.toInt() }
        // A small green patch to clone from.
        for (y in 5..15) for (x in 5..15) pixels[y * w + x] = 0xFF00FF00.toInt()

        val snapshot = pixels.copyOf()
        // Clone the green patch onto two overlapping destinations.
        val dab1 = CloneBrush.plan(10f, 10f, 20f, 10f, 4f, w, h)!!
        CloneBrush.applyClone(pixels, w, h, dab1, snapshot)
        val dab2 = CloneBrush.plan(10f, 10f, 22f, 10f, 4f, w, h)!!
        CloneBrush.applyClone(pixels, w, h, dab2, snapshot)

        // Both destinations should show the green from the snapshot, not read each other's output.
        val g1 = (pixels[10 * w + 20] shr 8) and 0xFF
        val g2 = (pixels[10 * w + 22] shr 8) and 0xFF
        assertTrue("first clone should be green, got G=$g1", g1 > 100)
        assertTrue("second clone should also be green from snapshot, got G=$g2", g2 > 100)
    }

    // --- edge cases -------------------------------------------------------------------------

    @Test
    fun cloneNearImageEdgeDoesNotCrash() {
        val w = 50
        val h = 50
        val pixels = IntArray(w * h) { 0xFFFF0000.toInt() }
        val dab = CloneBrush.plan(2f, 2f, 47f, 47f, 8f, w, h)
        if (dab != null) {
            CloneBrush.applyClone(pixels, w, h, dab)
            // No exception = pass.
        }
    }

    @Test
    fun healNearImageEdgeDoesNotCrash() {
        val w = 50
        val h = 50
        val pixels = IntArray(w * h) { 0xFF00FF00.toInt() }
        val dab = CloneBrush.plan(48f, 48f, 2f, 2f, 6f, w, h)
        if (dab != null) {
            CloneBrush.applyHeal(pixels, w, h, dab)
        }
    }

    @Test
    fun planWithNegativeRadiusReturnsNull() {
        assertNull(CloneBrush.plan(50f, 50f, 100f, 100f, -5f, 200, 200))
    }

    @Test
    fun planOnTinyImageWorks() {
        val dab = CloneBrush.plan(0.5f, 0.5f, 1.5f, 1.5f, 1f, 3, 3)
        assertNotNull(dab)
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Average RGB of a rectangular patch. */
    private fun averageRgb(pixels: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int): FloatArray {
        var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (dy in 0 until h) for (dx in 0 until w) {
            val c = pixels[(y0 + dy) * stride + (x0 + dx)]
            r += (c shr 16 and 0xFF)
            g += (c shr 8 and 0xFF)
            b += (c and 0xFF)
            n++
        }
        return floatArrayOf(r / n, g / n, b / n)
    }
}
