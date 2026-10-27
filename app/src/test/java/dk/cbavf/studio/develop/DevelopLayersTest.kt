package dk.cbavf.studio.develop

import dk.cbavf.studio.develop.model.ArtStyle
import dk.cbavf.studio.develop.model.ArtStyles
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.EditBlend
import dk.cbavf.studio.develop.model.Mask
import dk.cbavf.studio.develop.model.MaskCombine
import dk.cbavf.studio.develop.model.MaskKind
import dk.cbavf.studio.develop.model.MaskLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopLayersTest {
    private fun gradient(w: Int, h: Int) = IntArray(w * h) { i ->
        val v = (i % w) * 255 / (w - 1)
        (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or 128
    }

    @Test
    fun everyStyleKeepsSizeAndAlpha() {
        val w = 64
        val h = 48
        val px = gradient(w, h)
        px[0] = 0x00102030 // one transparent pixel stays transparent
        for (s in ArtStyle.entries) {
            val out = ArtStyles.apply(s, px, w, h)
            assertEquals(s.label, w * h, out.size)
            assertEquals(s.label, 0, out[0] ushr 24)
            assertEquals(s.label, 0xFF, out[w * h - 1] ushr 24)
        }
    }

    @Test
    fun sketchStylesLeaveFlatWhiteAlone() {
        val w = 32
        val px = IntArray(w * w) { 0xFFFFFFFF.toInt() }
        for (s in listOf(ArtStyle.Pencil, ArtStyle.Halftone, ArtStyle.Engraving)) {
            val out = ArtStyles.apply(s, px, w, w)
            assertTrue(s.label, out.all { (it and 0xFF) > 200 })
        }
    }

    @Test
    fun kuwaharaFlattensNoiseButKeepsAnEdge() {
        val w = 40
        val px = IntArray(w * w) { i ->
            val x = i % w
            val base = if (x < w / 2) 40 else 210
            val v = (base + ((i * 7919) % 21) - 10).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = ArtStyles.kuwahara(px, w, w, 4)
        val left = out[20 * w + 5] and 0xFF
        val right = out[20 * w + 35] and 0xFF
        assertTrue(left in 30..50)
        assertTrue(right in 200..220)
    }

    @Test
    fun maskCombinesAndLuminanceRange() {
        assertEquals(1f, MaskCombine.Add.apply(1f, 0.5f), 1e-6f)
        assertEquals(0.75f, MaskCombine.Add.apply(0.5f, 0.5f), 1e-6f)
        assertEquals(0.25f, MaskCombine.Subtract.apply(0.5f, 0.5f), 1e-6f)
        assertEquals(0.25f, MaskCombine.Intersect.apply(0.5f, 0.5f), 1e-6f)
        assertEquals(0.5f, MaskCombine.Difference.apply(1f, 0.5f), 1e-6f)
        val m = Mask(kind = MaskKind.Luminance, lumaLow = 0.4f, lumaHigh = 0.6f, feather = 0.1f)
        assertEquals(1f, m.lumaWeight(0.5f), 1e-6f)
        assertEquals(0f, m.lumaWeight(0.1f), 1e-6f)
        assertEquals(0.5f, m.lumaWeight(0.65f), 1e-5f)
    }

    @Test
    fun blendModesPerChannel() {
        assertEquals(0.25f, EditBlend.Multiply.channel(0.5f, 0.5f), 1e-6f)
        assertEquals(0.75f, EditBlend.Screen.channel(0.5f, 0.5f), 1e-6f)
        assertEquals(0.3f, EditBlend.Darken.channel(0.3f, 0.8f), 1e-6f)
        assertEquals(0.8f, EditBlend.Normal.channel(0.3f, 0.8f), 1e-6f)
    }

    @Test
    fun olderSingleMasksMoveIntoTheStack() {
        val old = DevelopDoc(mask = Mask(kind = MaskKind.Spot, spotHue = 120f))
        assertEquals(1, old.maskLayers.size)
        val s = old.withMaskStack()
        assertEquals(listOf(MaskLayer(Mask(kind = MaskKind.Spot, spotHue = 120f))), s.masks)
        assertEquals(MaskKind.None, s.mask.kind)
        // Presets keep the look, not the masks.
        assertTrue(s.asPreset().maskLayers.isEmpty())
    }

    @Test
    fun scopesCountEveryOpaquePixel() {
        val px = IntArray(100) { if (it < 50) 0xFFFF0000.toInt() else 0xFF808080.toInt() }
        val d = ScopeData.of(px)
        assertEquals(50, d.red[255])
        assertEquals(50, d.red[128])
        assertEquals(100, d.vector.sum())
        // Grey sits in the centre of the vectorscope.
        val n = ScopeData.VECTOR_N
        assertEquals(50, d.vector[(n / 2) * n + n / 2])
    }
}
