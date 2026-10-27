package dk.cbavf.studio.uieditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiOrientationTest {
    private val modules = (1..8).map { i -> ModuleSeed("m$i", "Module $i", listOf(ControlSeed("Level", ControlKind.Slider), ControlSeed("On", ControlKind.Toggle))) }
    private val portrait = UiModel.seed(modules)

    @Test fun portraitViewIsTheLayoutItself() {
        assertEquals(portrait, UiOrientation.view(portrait, landscape = false))
    }

    @Test fun anUneditedLandscapeIsPortraitInWiderRows() {
        val land = UiOrientation.view(portrait, landscape = true)
        // Six panels fit on the first row instead of four.
        assertEquals(0f, land.panels.first { it.id == "m6" }.y, 0f)
        assertTrue(land.panels.first { it.id == "m7" }.y > 0f)
        assertEquals(portrait.controls.map { it.id }, land.controls.map { it.id })
    }

    @Test fun reflowMovesControlsWithTheirPanel() {
        val land = UiOrientation.reflow(portrait, 6)
        for (m in listOf("m5", "m6", "m7")) {
            val before = portrait.panels.first { it.id == m }
            val after = land.panels.first { it.id == m }
            val dx = after.x - before.x
            val dy = after.y - before.y
            val c0 = portrait.controls.first { it.id == "$m.Level" }
            val c1 = land.controls.first { it.id == "$m.Level" }
            assertEquals(c0.x + dx, c1.x, 0f)
            assertEquals(c0.y + dy, c1.y, 0f)
        }
    }

    @Test fun reflowKeepsReadingOrderAndPanelSizes() {
        val land = UiOrientation.reflow(portrait, 3)
        assertEquals(0f, land.panels.first { it.id == "m1" }.x, 0f)
        assertEquals(0f, land.panels.first { it.id == "m4" }.x, 0f)
        assertEquals(portrait.panels.map { it.w to it.h }, land.panels.map { it.w to it.h })
    }

    @Test fun anEditInLandscapeLeavesPortraitAlone() {
        val doc = UiOrientation.edit(portrait, landscape = true) { UiModel.movePanel(it, "m1", 100f, 0f) }
        assertNotNull(doc.landscape)
        assertEquals(portrait.panels, doc.panels)
        assertEquals(portrait.controls, doc.controls)
        assertEquals(100f, UiOrientation.view(doc, true).panels.first { it.id == "m1" }.x, 0f)
    }

    @Test fun anEditInPortraitLeavesLandscapeAlone() {
        val land = UiOrientation.edit(portrait, landscape = true) { UiModel.movePanel(it, "m1", 100f, 0f) }
        val doc = UiOrientation.edit(land, landscape = false) { UiModel.movePanel(it, "m2", 0f, 40f) }
        assertEquals(land.landscape, doc.landscape)
        assertEquals(portrait.panels.first { it.id == "m2" }.y + 40f, doc.panels.first { it.id == "m2" }.y, 0f)
    }

    @Test fun cablesAreSharedByBothOrientations() {
        val doc = UiOrientation.edit(portrait, landscape = true) { UiModel.connect(it, "m1.On", "m2.Level")!! }
        assertEquals(1, doc.cables.size)
        assertEquals(doc.cables, UiOrientation.view(doc, false).cables)
        assertEquals(doc.cables, UiOrientation.view(doc, true).cables)
    }

    @Test fun mergeAddsNewControlsToBothFronts() {
        val doc = UiOrientation.edit(portrait, landscape = true) { UiModel.movePanel(it, "m1", 100f, 0f) }
        val offered = modules.map { m -> if (m.id == "m1") ModuleSeed(m.id, m.title, m.controls + ControlSeed("Speed", ControlKind.Slider)) else m }
        val merged = UiOrientation.merge(doc, offered)
        assertTrue(merged.controls.any { it.id == "m1.Speed" })
        assertTrue(merged.landscape!!.controls.any { it.id == "m1.Speed" })
        // The landscape move survives the merge.
        assertEquals(100f, merged.landscape!!.panels.first { it.id == "m1" }.x, 0f)
    }

    @Test fun mergeWithoutALandscapeFrontKeepsItUnset() {
        assertNull(UiOrientation.merge(portrait, modules).landscape)
    }

    @Test fun resetMakesLandscapeFollowPortraitAgain() {
        val doc = UiOrientation.edit(portrait, landscape = true) { UiModel.movePanel(it, "m1", 100f, 0f) }
        val reset = UiOrientation.resetLandscape(doc)
        assertNull(reset.landscape)
        assertEquals(UiOrientation.reflow(portrait, UiOrientation.LANDSCAPE_COLUMNS), UiOrientation.view(reset, true))
    }
}
