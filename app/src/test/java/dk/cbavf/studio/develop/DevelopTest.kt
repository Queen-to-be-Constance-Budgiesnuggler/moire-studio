package dk.cbavf.studio.develop

import dk.cbavf.studio.develop.model.BandAdjust
import dk.cbavf.studio.develop.model.ColorMath
import dk.cbavf.studio.develop.model.CurvePoint
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.HueBand
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.develop.model.Mask
import dk.cbavf.studio.develop.model.MaskKind
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopTest {
    private fun lookup(lut: Lut, r: Float, g: Float, b: Float) = FloatArray(3).also { lut.apply(r, g, b, it) }

    @Test
    fun neutralEditIsTheIdentity() {
        val lut = Lut.build(DevelopDoc())
        for (c in listOf(Triple(0f, 0f, 0f), Triple(0.2f, 0.5f, 0.9f), Triple(1f, 1f, 1f), Triple(0.33f, 0.1f, 0.77f))) {
            val out = lookup(lut, c.first, c.second, c.third)
            assertEquals(c.first, out[0], 2e-3f)
            assertEquals(c.second, out[1], 2e-3f)
            assertEquals(c.third, out[2], 2e-3f)
        }
    }

    @Test
    fun oneStopDoublesLinearLight() {
        val out = lookup(Lut.build(DevelopDoc(exposureEv = 1f)), 0.5f, 0.5f, 0.5f)
        // sRGB 0.5 = 0.214 linear; doubled = 0.428 linear = sRGB ~0.686.
        assertEquals(0.686f, out[0], 6e-3f)
    }

    @Test
    fun hslRoundTrips() {
        val hsl = FloatArray(3)
        val rgb = FloatArray(3)
        for (c in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0.2f, 0.6f, 0.3f), floatArrayOf(0.9f, 0.85f, 0.1f))) {
            ColorMath.rgbToHsl(c[0], c[1], c[2], hsl)
            ColorMath.hslToRgb(hsl[0], hsl[1], hsl[2], rgb)
            for (k in 0..2) assertEquals(c[k], rgb[k], 1e-4f)
        }
    }

    @Test
    fun hueBandsBlendAndOnlyTouchTheirColours() {
        assertEquals(1f, ColorMath.bandWeight(0f, HueBand.Red), 0f)
        assertEquals(0.5f, ColorMath.bandWeight(15f, HueBand.Red), 1e-4f)
        assertEquals(0.5f, ColorMath.bandWeight(15f, HueBand.Orange), 1e-4f)
        assertEquals(1f, ColorMath.bandWeight(350f, HueBand.Red) + ColorMath.bandWeight(350f, HueBand.Magenta), 1e-4f)
        // Desaturating greens leaves a pure blue alone.
        val lut = Lut.build(DevelopDoc(bands = mapOf(HueBand.Green to BandAdjust(saturation = -1f))))
        val blue = lookup(lut, 0f, 0f, 1f)
        assertEquals(1f, blue[2], 2e-3f)
        val green = lookup(lut, 0f, 1f, 0f)
        assertEquals(green[0], green[1], 2e-2f) // grey now
    }

    @Test
    fun toneCurveIsMonotoneAndHitsItsPoints() {
        val curve = ColorMath.ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.4f), CurvePoint(0.3f, 0.42f), CurvePoint(1f, 1f)))
        assertEquals(0.4f, curve(0.25f), 1e-5f)
        var last = -1f
        for (i in 0..1000) {
            val y = curve(i / 1000f)
            assertTrue("not monotone at $i", y >= last - 1e-6f)
            last = y
        }
    }

    @Test
    fun cubeFilesRoundTrip() {
        val lut = Lut.build(DevelopDoc(temperature = 0.5f, gamma = 1.4f), size = 9)
        val parsed = Lut.parseCube(lut.toCube("test"))
        assertEquals(9, parsed.size)
        for (i in lut.data.indices) assertEquals(lut.data[i], parsed.data[i], 1e-5f)
        // A hand-written 2³ identity with comments and a domain.
        val text = "# comment\nLUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" +
            "0 0 0\n2 0 0\n0 2 0\n2 2 0\n0 0 2\n2 0 2\n0 2 2\n2 2 2\n"
        val id = Lut.parseCube(text)
        assertEquals(0.5f, lookup(id, 0.5f, 0.25f, 0.75f)[0], 1e-6f)
    }

    @Test
    fun presetsKeepTheLookNotTheImage() {
        val doc = DevelopDoc(sourceAssetId = "a", exposureEv = 1f, mask = Mask(MaskKind.Spot), styleAssetId = "s", styleAmount = 0.5f)
        val other = DevelopDoc(sourceAssetId = "b").withPreset(doc.asPreset())
        assertEquals("b", other.sourceAssetId)
        assertEquals(1f, other.exposureEv, 0f)
        assertEquals(MaskKind.None, other.mask.kind)
        val json = ProjectStore.json.encodeToString(Recipe.serializer(), Recipe(develop = doc))
        assertEquals(doc, ProjectStore.json.decodeFromString(Recipe.serializer(), json).develop)
    }
}
