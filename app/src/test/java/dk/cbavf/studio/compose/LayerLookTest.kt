package dk.cbavf.studio.compose

import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.develop.model.BandAdjust
import dk.cbavf.studio.develop.model.CurvePoint
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.HueBand
import dk.cbavf.studio.develop.model.Lut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerLookTest {
    private fun state() = ComposeState().apply {
        edit.load(ComposeDoc(400, 300))
        addSolid(0xFF808080)
    }

    /** A cube that swaps red and blue. */
    private fun swapRedBlue(size: Int = 9): Lut {
        val id = Lut.identity(size)
        val d = id.data.copyOf()
        for (i in 0 until size * size * size) {
            val r = d[i * 3]
            d[i * 3] = d[i * 3 + 2]
            d[i * 3 + 2] = r
        }
        return Lut(size, d)
    }

    private fun lookup(l: Lut, r: Float, g: Float, b: Float) = FloatArray(3).also { l.apply(r, g, b, it) }

    @Test
    fun aCubeAtFullAmountReplacesTheColour() {
        val out = lookup(Lut.identity(9).then(swapRedBlue(), 1f), 0.75f, 0.5f, 0.25f)
        assertEquals(0.25f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(0.75f, out[2], 1e-4f)
    }

    @Test
    fun aCubeAtHalfAmountMixesHalfWay() {
        val out = lookup(Lut.identity(9).then(swapRedBlue(), 0.5f), 0.75f, 0.5f, 0.25f)
        assertEquals(0.5f, out[0], 1e-4f)
        assertEquals(0.5f, out[2], 1e-4f)
    }

    @Test
    fun aCubeAtZeroAmountLeavesTheTableAlone() {
        val base = Lut.identity(9)
        assertSame(base, base.then(swapRedBlue(), 0f))
    }

    @Test
    fun theCubeComesAfterTheLook() {
        // The look darkens red only; the cube then moves that red into blue.
        val look = Lut.build(DevelopDoc(exposureEv = -1f), size = 9)
        val both = look.then(swapRedBlue(), 1f)
        val lookOnly = lookup(look, 1f, 0f, 0f)
        val out = lookup(both, 1f, 0f, 0f)
        assertEquals(lookOnly[0], out[2], 1e-3f)
        assertEquals(0f, out[0], 1e-3f)
    }

    @Test
    fun theLayerLookTakesTheCurveAndMixer() {
        val s = state()
        s.adjust("curve0") { it.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))) }
        s.adjust("Red.s") { it.copy(bands = it.bands + (HueBand.Red to BandAdjust(saturation = -1f))) }
        val a = s.selected!!.adjust!!
        assertEquals(3, a.curve.size)
        assertEquals(-1f, a.bands.getValue(HueBand.Red).saturation, 0f)
        assertTrue(a.summary.contains("curve"))
        assertTrue(a.summary.contains("hue mixer"))
    }

    @Test
    fun aSliderDragOnTheLookIsOneUndoStep() {
        val s = state()
        for (k in 1..10) s.adjust("ev") { it.copy(exposureEv = k / 10f) }
        assertEquals(1f, s.selected!!.adjust!!.exposureEv, 1e-6f)
        s.edit.undo()
        assertNull(s.selected!!.adjust)
    }

    @Test
    fun aLookBackAtNeutralIsDropped() {
        val s = state()
        s.adjust("ev") { it.copy(exposureEv = 1f) }
        s.adjust("ev") { it.copy(exposureEv = 0f) }
        assertNull(s.selected!!.adjust)
    }
}
