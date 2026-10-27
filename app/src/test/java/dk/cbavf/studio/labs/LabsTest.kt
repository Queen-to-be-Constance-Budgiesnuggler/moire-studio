package dk.cbavf.studio.labs

import dk.cbavf.studio.fft.model.Fft
import dk.cbavf.studio.fft.model.FftDoc
import dk.cbavf.studio.fft.model.FilterShape
import dk.cbavf.studio.fft.model.SpectrumBrush
import dk.cbavf.studio.fft.model.SpectrumDab
import dk.cbavf.studio.fft.model.SpectrumMask
import dk.cbavf.studio.glitch.model.ChannelOrder
import dk.cbavf.studio.glitch.model.FillMode
import dk.cbavf.studio.glitch.model.Glitch
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.glitch.model.SweepParam
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

class LabsTest {
    @Test
    fun fftRoundTrips() {
        val n = 64
        val rnd = Random(3)
        val orig = FloatArray(n * n) { rnd.nextFloat() }
        val re = orig.copyOf()
        val im = FloatArray(n * n)
        Fft.transform2d(re, im, n, inverse = false)
        Fft.transform2d(re, im, n, inverse = true)
        for (i in orig.indices) {
            assertEquals(orig[i], re[i], 1e-4f)
            assertEquals(0f, im[i], 1e-4f)
        }
    }

    @Test
    fun aGratingIsTwoPeaks() {
        // 8 periods across a 64-pixel row: all energy at (±8, 0) besides the DC term.
        val n = 64
        val re = FloatArray(n * n) { i -> (0.5 + 0.5 * cos(2 * PI * 8 * (i % n) / n)).toFloat() }
        val im = FloatArray(n * n)
        Fft.transform2d(re, im, n, inverse = false)
        val mag = { x: Int, y: Int -> hypot(re[y * n + x], im[y * n + x]) }
        assertEquals(n * n * 0.25f, mag(8, 0), 1e-1f)
        assertEquals(n * n * 0.25f, mag(n - 8, 0), 1e-1f)
        assertEquals(0f, mag(9, 0), 1e-2f)
    }

    @Test
    fun spectrumMasksAreSymmetricAndKeepTheAverage() {
        val n = 32
        val doc = FftDoc(size = n, filter = FilterShape.HighPass, radius = 0.2f, dabs = listOf(SpectrumDab(0.25f, 0.1f, 0.05f, SpectrumBrush.Keep)))
        val g = SpectrumMask.build(doc)
        assertEquals(1f, g[0], 0f) // DC survives the high-pass
        for (y in 0 until n) for (x in 0 until n) {
            // Gain at f equals gain at −f, so the inverse stays real.
            assertEquals(g[y * n + x], g[((n - y) % n) * n + (n - x) % n], 0f)
        }
        assertEquals(0f, g[1], 0f) // a low frequency, removed
    }

    @Test
    fun boostBringsBackFilteredFrequencies() {
        // High-pass removes everything near the centre; a boost dab there brings it back amplified.
        val n = 32
        val doc = FftDoc(size = n, filter = FilterShape.HighPass, radius = 0.3f, boost = 5f, dabs = listOf(SpectrumDab(0.0625f, 0f, 0.01f, SpectrumBrush.Boost)))
        val g = SpectrumMask.build(doc)
        assertEquals(5f, g[2], 0f)        // (+2, 0): painted
        assertEquals(5f, g[n - 2], 0f)    // (−2, 0): its mirror
        assertEquals(0f, g[3], 0f)        // next to it: still filtered
    }

    @Test
    fun widthMisreadShearsRows() {
        // 4×2 RGBA image; read back one pixel wider, row 2 starts one pixel late in the stream.
        val w = 4
        val h = 2
        val rgba = ByteArray(w * h * 4) { (it / 4).toByte() } // pixel index in every channel
        val out = Glitch.apply(rgba, w, h, GlitchDoc(widthDelta = 1))
        assertEquals(5, out.width)
        assertEquals(1, out.height) // 8 pixels hold one 5-wide row
        assertEquals(4, out.argb[4] and 0xFF)
    }

    @Test
    fun bytesPerPixelAndChannelOrder() {
        val rgba = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        // Packed RGB read of RGBA data: the alpha byte becomes the next pixel's red.
        val rgb = Glitch.apply(rgba, 3, 1, GlitchDoc(bytesPerPixel = 3, widthDelta = 1))
        assertEquals((40 shl 16) or (50 shl 8) or 60, rgb.argb[1] and 0xFFFFFF)
        val bgr = Glitch.apply(rgba, 3, 1, GlitchDoc(channels = ChannelOrder.BGR))
        assertEquals((30 shl 16) or (20 shl 8) or 10, bgr.argb[0] and 0xFFFFFF)
    }

    @Test
    fun fillsTouchOnlyTheirRange() {
        val bytes = ByteArray(100) { it.toByte() }
        val zero = Glitch.fill(bytes.copyOf(), GlitchDoc(fill = FillMode.Zero, fillStart = 0.5f, fillLength = 0.1f))
        assertEquals(0, zero[55].toInt())
        assertEquals(49, zero[49].toInt())
        assertEquals(60, zero[60].toInt())
        val rep = Glitch.fill(bytes.copyOf(), GlitchDoc(fill = FillMode.Repeat, fillStart = 0.5f, fillLength = 0.1f))
        assertEquals(40, rep[50].toInt()) // copies the 10 bytes before the range
    }

    @Test
    fun corruptedJpegsStillDecode() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, (x * 4 shl 16) or (y * 4 shl 8) or 128)
        val jpeg = ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }
        val bad = Glitch.corruptJpeg(jpeg, 20, seed = 7)
        assertTrue(!bad.contentEquals(jpeg))
        // Headers untouched: everything up to the scan data is identical.
        val sos = (2 until jpeg.size - 1).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xDA.toByte() }
        assertArrayEquals(jpeg.copyOf(sos + 4), bad.copyOf(sos + 4))
        val decoded = ImageIO.read(ByteArrayInputStream(bad))
        assertEquals(64, decoded.width)
        // Same seed, same damage.
        assertArrayEquals(bad, Glitch.corruptJpeg(jpeg, 20, seed = 7))
    }

    @Test
    fun sweepCentresOnTheCurrentValue() {
        val v = SweepParam.Stride.variants(GlitchDoc(strideDelta = 10), 9, 4)
        assertEquals(listOf(-6, -2, 2, 6, 10, 14, 18, 22, 26), v.map { it.strideDelta })
    }

    @Test
    fun labRecipesRoundTrip() {
        val r = Recipe(fft = FftDoc("a", 256, FilterShape.BandPass, phaseFromAssetId = "b"), glitch = GlitchDoc("a", generations = 3, corruptBytes = 9))
        val json = ProjectStore.json.encodeToString(Recipe.serializer(), r)
        assertEquals(r, ProjectStore.json.decodeFromString(Recipe.serializer(), json))
    }
}
