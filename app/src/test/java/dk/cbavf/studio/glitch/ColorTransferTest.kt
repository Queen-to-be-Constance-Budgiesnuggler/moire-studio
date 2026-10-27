package dk.cbavf.studio.glitch

import dk.cbavf.studio.glitch.model.ColorTransfer
import dk.cbavf.studio.glitch.model.Glitch
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.glitch.model.Pixels
import dk.cbavf.studio.glitch.model.TransferSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class ColorTransferTest {
    private fun rgb(r: Int, g: Int, b: Int, a: Int = 0xFF) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(c: Int, shift: Int) = c shr shift and 0xFF

    private fun noise(seed: Long, size: Int, lo: Int, hi: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(size) { rgb(lo + rnd.nextInt(hi - lo), lo + rnd.nextInt(hi - lo), lo + rnd.nextInt(hi - lo)) }
    }

    @Test
    fun labRoundTripsWithinOneStep() {
        val v = FloatArray(3)
        val rnd = Random(2)
        val colours = listOf(rgb(0, 0, 0), rgb(255, 255, 255), rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255), rgb(3, 2, 1)) +
            List(200) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        for (c in colours) {
            ColorTransfer.toLab(c, v)
            val back = ColorTransfer.fromLab(v[0], v[1], v[2])
            for (s in listOf(16, 8, 0)) assertTrue("${Integer.toHexString(c)} -> ${Integer.toHexString(back)}", abs(ch(c, s) - ch(back, s)) <= 1)
        }
    }

    @Test
    fun whiteIsLightness100AndNeutral() {
        val v = FloatArray(3)
        ColorTransfer.toLab(rgb(255, 255, 255), v)
        assertEquals(100f, v[0], 0.05f)
        assertEquals(0f, v[1], 0.05f)
        assertEquals(0f, v[2], 0.05f)
    }

    @Test
    fun rgbStatsAreMeanAndSpread() {
        val s = ColorTransfer.stats(intArrayOf(rgb(10, 0, 50), rgb(30, 0, 50)), TransferSpace.Rgb)
        assertArrayEquals(floatArrayOf(20f, 0f, 50f), s.mean, 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 0f, 0f), s.std, 1e-4f)
    }

    @Test
    fun aFullRgbTransferTakesOnThePalettesStatistics() {
        val target = noise(1, 4000, 80, 120)
        val palette = noise(2, 3000, 100, 180)
        val out = ColorTransfer.transfer(target, palette, TransferSpace.Rgb, 1f)
        val want = ColorTransfer.stats(palette, TransferSpace.Rgb)
        val got = ColorTransfer.stats(out, TransferSpace.Rgb)
        for (c in 0 until 3) {
            assertEquals(want.mean[c], got.mean[c], 0.6f)
            assertEquals(want.std[c], got.std[c], 0.6f)
        }
    }

    @Test
    fun aFullLabTransferTakesOnThePalettesLightness() {
        val target = noise(3, 4000, 20, 60)
        val palette = noise(4, 3000, 150, 200)
        val out = ColorTransfer.transfer(target, palette, TransferSpace.Lab, 1f)
        val want = ColorTransfer.stats(palette, TransferSpace.Lab)
        val got = ColorTransfer.stats(out, TransferSpace.Lab)
        assertEquals(want.mean[0], got.mean[0], 1f)
        assertEquals(want.std[0], got.std[0], 1f)
    }

    @Test
    fun amountZeroChangesNothing() {
        val target = noise(5, 500, 0, 256)
        assertArrayEquals(target, ColorTransfer.transfer(target, noise(6, 500, 0, 256), TransferSpace.Lab, 0f))
    }

    @Test
    fun halfAmountGoesHalfWay() {
        val target = IntArray(10) { rgb(100, 100, 100) }
        val palette = IntArray(10) { rgb(200, 200, 200) }
        val out = ColorTransfer.transfer(target, palette, TransferSpace.Rgb, 0.5f)
        out.forEach { assertEquals(rgb(150, 150, 150), it) }
    }

    @Test
    fun aFlatTargetIsShiftedNotBlownUp() {
        val target = IntArray(10) { rgb(100, 100, 100) }
        val palette = intArrayOf(rgb(150, 150, 150), rgb(250, 250, 250))
        val out = ColorTransfer.transfer(target, palette, TransferSpace.Rgb, 1f)
        out.forEach { assertEquals(rgb(200, 200, 200), it) }
    }

    @Test
    fun alphaIsKept() {
        val target = intArrayOf(rgb(10, 20, 30, 0x40), rgb(90, 80, 70, 0x80))
        val out = ColorTransfer.transfer(target, noise(7, 50, 100, 200), TransferSpace.Lab, 1f)
        assertEquals(0x40, out[0] ushr 24)
        assertEquals(0x80, out[1] ushr 24)
    }

    @Test
    fun pixelStagesNeedAColourImageToTransfer() {
        val px = Pixels(IntArray(4) { rgb(100, 100, 100) }, 2, 2)
        val palette = IntArray(4) { rgb(200, 200, 200) }
        val doc = GlitchDoc(colorSpace = TransferSpace.Rgb)
        assertArrayEquals(px.argb, Glitch.pixelStages(px, doc, palette).argb)
        assertArrayEquals(palette, Glitch.pixelStages(px, doc.copy(colorFromAssetId = "b"), palette).argb)
    }
}
