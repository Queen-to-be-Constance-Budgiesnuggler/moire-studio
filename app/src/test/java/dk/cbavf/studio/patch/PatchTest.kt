package dk.cbavf.studio.patch

import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import dk.cbavf.studio.draw.model.SubStroke
import dk.cbavf.studio.draw.model.Transform
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.patch.model.Curve
import dk.cbavf.studio.patch.model.Lfo
import dk.cbavf.studio.patch.model.LfoShape
import dk.cbavf.studio.patch.model.Param
import dk.cbavf.studio.patch.model.PatchDoc
import dk.cbavf.studio.patch.model.PatchMath
import dk.cbavf.studio.patch.model.Route
import dk.cbavf.studio.patch.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchTest {
    private val route = Route(1, SourceKind.Pitch, layerId = 1, param = Param.Angle, min = -10f, max = 10f)

    @Test
    fun sourcesNormalizeAndClamp() {
        assertEquals(0.5f, PatchMath.normalize(SourceKind.Pitch, 0f), 1e-6f)
        assertEquals(1f, PatchMath.normalize(SourceKind.Pitch, 200f), 0f)
        assertEquals(0f, PatchMath.normalize(SourceKind.Mic, -90f), 0f)
    }

    @Test
    fun routesMapThroughTheirCurve() {
        assertEquals(0f, PatchMath.map(route, 0.5f), 1e-5f)
        // Exponential keeps the low end fine-grained: halfway in gives 1/8 of the range.
        assertEquals(-10f + 20f / 8, PatchMath.map(route.copy(curve = Curve.Exponential), 0.5f), 1e-4f)
        // A reversed range runs the parameter backwards.
        assertEquals(10f, PatchMath.map(route.copy(min = 10f, max = -10f), 0f), 0f)
        assertEquals(listOf(0f, 1f / 7, 1f), listOf(0f, 0.2f, 1f).map { PatchMath.curve(it, Curve.Steps) })
    }

    @Test
    fun smoothingIsFrameRateIndependent() {
        // One second at 60 fps and at 120 fps lands in the same place.
        var a = 0f
        repeat(60) { a = PatchMath.smooth(a, 1f, 0.5f, 1f / 60) }
        var b = 0f
        repeat(120) { b = PatchMath.smooth(b, 1f, 0.5f, 1f / 120) }
        assertEquals(a, b, 1e-4f)
        assertTrue(a in 0.8f..0.9f)
        assertEquals(1f, PatchMath.smooth(0f, 1f, 0f, 0.016f), 0f)
    }

    @Test
    fun lfoShapes() {
        val sine = Lfo(LfoShape.Sine, rateHz = 1f)
        assertEquals(0.5f, PatchMath.lfo(sine, 0.0), 1e-6f)
        assertEquals(1f, PatchMath.lfo(sine, 0.25), 1e-6f)
        assertEquals(1f, PatchMath.lfo(Lfo(LfoShape.Triangle, 1f), 0.5), 1e-6f)
        assertEquals(0f, PatchMath.lfo(Lfo(LfoShape.Square, 1f), 0.75), 0f)
        assertEquals(0.25f, PatchMath.lfo(Lfo(LfoShape.Saw, 1f), 2.25), 1e-6f)
        // Random holds one value per cycle.
        val r = Lfo(LfoShape.Random, 1f)
        assertEquals(PatchMath.lfo(r, 3.1), PatchMath.lfo(r, 3.9), 0f)
    }

    @Test
    fun parametersLandOnTheRightContent() {
        val g = PatchMath.apply(Layer(1, Grating()), Param.Spacing, 1f).content as Grating
        assertEquals(2f, g.spacingPx, 0f) // clamped to the minimum spacing
        val s = Stroke(listOf(Pt(0f, 0f), Pt(1f, 0f)), Transform(), listOf(SubStroke()))
        val moved = PatchMath.apply(Layer(2, s), Param.MoveX, 300f).content as Stroke
        assertEquals(300f, moved.transform.tx, 0f)
        // A parameter the content doesn't have is ignored.
        assertEquals(Layer(1, Grating()), PatchMath.apply(Layer(1, Grating()), Param.Rays, 99f))
    }

    @Test
    fun patchRoundTripsThroughJson() {
        val doc = PatchDoc(listOf(route.copy(remote = true, curve = Curve.Logarithmic)), lfo1 = Lfo(LfoShape.Random, 2f))
        val json = ProjectStore.json.encodeToString(PatchDoc.serializer(), doc)
        assertEquals(doc, ProjectStore.json.decodeFromString(PatchDoc.serializer(), json))
    }
}
