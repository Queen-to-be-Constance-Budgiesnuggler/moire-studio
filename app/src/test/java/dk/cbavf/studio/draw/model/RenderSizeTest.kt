package dk.cbavf.studio.draw.model

import dk.cbavf.studio.draw.DrawState
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSizeTest {
    @Test
    fun screenKeepsTheCanvasSize() {
        val p = RenderSizes.plan(RenderSize.Screen, 1080, 2340)
        assertEquals(1080, p.widthPx)
        assertEquals(2340, p.heightPx)
        assertEquals(1f, p.scale, 0f)
        assertFalse(p.clamped)
    }

    @Test
    fun screenIsNeverLowered() {
        val p = RenderSizes.plan(RenderSize.Screen, 10_000, 10_000, maxPixels = 1_000)
        assertEquals(10_000, p.widthPx)
        assertFalse(p.clamped)
    }

    @Test
    fun twoTimesDoublesBothSides() {
        val p = RenderSizes.plan(RenderSize.X2, 1080, 1700)
        assertEquals(2160, p.widthPx)
        assertEquals(3400, p.heightPx)
        assertEquals(2f, p.scale, 0f)
        assertFalse(p.clamped)
    }

    @Test
    fun threeTimesTriplesBothSides() {
        val p = RenderSizes.plan(RenderSize.X3, 1000, 1500)
        assertEquals(3000, p.widthPx)
        assertEquals(4500, p.heightPx)
    }

    @Test
    fun longSideSizeSetsTheTallSide() {
        val p = RenderSizes.plan(RenderSize.Long4096, 1080, 2340)
        assertEquals(4096, p.heightPx)
        assertEquals(1890, p.widthPx)
    }

    @Test
    fun longSideSizeSetsTheWideSide() {
        val p = RenderSizes.plan(RenderSize.Long4096, 2340, 1080)
        assertEquals(4096, p.widthPx)
        assertEquals(1890, p.heightPx)
    }

    @Test
    fun longSideIsExactForAwkwardCanvasSizes() {
        for (h in listOf(1717, 1999, 2337, 2401, 3001)) {
            assertEquals(6000, RenderSizes.plan(RenderSize.Long6000, 900, h).heightPx)
        }
    }

    @Test
    fun fourTimesOnATallPhoneCanvasIsLoweredToTheLimit() {
        val p = RenderSizes.plan(RenderSize.X4, 1080, 2340)
        assertTrue(p.clamped)
        assertTrue(p.scale < 4f)
        assertTrue(p.pixels <= RenderSizes.MAX_PIXELS)
        // Lowered only as far as needed.
        assertTrue(p.pixels > RenderSizes.MAX_PIXELS * 99 / 100)
    }

    @Test
    fun loweringKeepsTheShape() {
        val p = RenderSizes.plan(RenderSize.X4, 1080, 2340)
        assertEquals(1080.0 / 2340, p.widthPx.toDouble() / p.heightPx, 0.001)
    }

    @Test
    fun aSmallLimitStillFits() {
        val max = 1_000_000L
        val p = RenderSizes.plan(RenderSize.X4, 1080, 2340, maxPixels = max)
        assertTrue(p.pixels <= max)
        assertTrue(p.clamped)
    }

    @Test
    fun subtractingLayersShareTheLimitBetweenThreeBitmaps() {
        val one = RenderSizes.plan(RenderSize.X3, 1080, 2340, bitmaps = 1)
        val three = RenderSizes.plan(RenderSize.X3, 1080, 2340, bitmaps = RenderSizes.bitmapsFor(subtracts = true))
        assertFalse(one.clamped)
        assertTrue(three.clamped)
        assertTrue(three.pixels * 3 <= RenderSizes.MAX_PIXELS)
    }

    @Test
    fun onlySubtractingLayersNeedExtraBitmaps() {
        assertEquals(3, RenderSizes.bitmapsFor(subtracts = true))
        assertEquals(1, RenderSizes.bitmapsFor(subtracts = false))
    }

    @Test
    fun anEmptyCanvasStillGivesOnePixel() {
        val p = RenderSizes.plan(RenderSize.X2, 0, 0)
        assertTrue(p.widthPx >= 1)
        assertTrue(p.heightPx >= 1)
    }

    @Test
    fun describeScreen() {
        assertEquals("Screen size: 1080 × 2340 px.", RenderSizes.describe(RenderSizes.plan(RenderSize.Screen, 1080, 2340)))
    }

    @Test
    fun describeSaysWhenTheSizeWasLowered() {
        val text = RenderSizes.describe(RenderSizes.plan(RenderSize.X4, 1080, 2340))
        assertTrue(text.startsWith("4×: "))
        assertTrue(text.contains("4× is too big"))
    }

    @Test
    fun describeAFittingSize() {
        assertEquals("2×: 2160 × 3400 px.", RenderSizes.describe(RenderSizes.plan(RenderSize.X2, 1080, 1700)))
    }

    @Test
    fun savedNoteIsEmptyOnScreen() {
        assertEquals("", RenderSizes.savedNote(RenderSizes.plan(RenderSize.Screen, 1080, 2340)))
    }

    @Test
    fun savedNoteGivesTheSize() {
        assertEquals(", 2160 × 3400 px", RenderSizes.savedNote(RenderSizes.plan(RenderSize.X2, 1080, 1700)))
    }

    @Test
    fun tooBigMessages() {
        assertEquals("Too big to save.", RenderSizes.tooBigMessage(RenderSize.Screen))
        assertTrue(RenderSizes.tooBigMessage(RenderSize.X4).contains("smaller size"))
    }

    @Test
    fun olderRecipesAreScreenExact() {
        val doc = ProjectStore.json.decodeFromString(CanvasDoc.serializer(), """{"widthPx":10,"heightPx":10,"layers":[]}""")
        assertEquals(RenderSize.Screen, doc.render)
    }

    @Test
    fun aLoadedCanvasBringsItsRenderSize() {
        val s = DrawState()
        s.load(s.toDoc().copy(widthPx = 100, heightPx = 100, render = RenderSize.X2), null)
        assertEquals(RenderSize.X2, s.renderSize)
        assertEquals(RenderSize.X2, s.toDoc().render)
    }

    @Test
    fun theDrawPlanUsesTheCanvasSize() {
        val s = DrawState()
        s.canvasWidth = 1000
        s.canvasHeight = 1500
        s.renderSize = RenderSize.X2
        val p = s.renderPlan()
        assertEquals(2000, p.widthPx)
        assertEquals(3000, p.heightPx)
    }

    @Test
    fun aSubtractingLayerLowersTheDrawPlan() {
        val s = DrawState()
        s.canvasWidth = 1080
        s.canvasHeight = 2340
        s.renderSize = RenderSize.X3
        assertFalse(s.renderPlan().clamped)
        s.updatePrimary { it.copy(blend = Blend.Subtract) }
        assertTrue(s.renderPlan().clamped)
    }

    @Test
    fun theRenderSizeIsKeptWithTheCanvas() {
        val doc = CanvasDoc(10, 10, emptyList(), render = RenderSize.Long4096)
        val json = ProjectStore.json.encodeToString(CanvasDoc.serializer(), doc)
        assertEquals(RenderSize.Long4096, ProjectStore.json.decodeFromString(CanvasDoc.serializer(), json).render)
    }
}
