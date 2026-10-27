package dk.cbavf.studio.uieditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelTest {
    private val modules = listOf(
        ModuleSeed("draw", "Draw", listOf(ControlSeed("Spacing", ControlKind.Slider), ControlSeed("Save", ControlKind.Button))),
        ModuleSeed("cam", "Camera", listOf(ControlSeed("Torch", ControlKind.Toggle))),
    )

    @Test fun seedPlacesPanelsAndControls() {
        val l = UiModel.seed(modules, columns = 2)
        assertEquals(2, l.panels.size)
        assertEquals(3, l.controls.size)
        assertEquals("draw.Spacing", l.controls[0].id)
        assertEquals(0f, l.panels[0].x, 0f)
        assertEquals(PANEL_W + PANEL_GAP, l.panels[1].x, 0f)
        // Controls sit inside their panel's header-to-bottom band.
        val p = l.panels[0]
        for (c in l.controls.filter { it.module == "draw" }) {
            assertTrue(c.x >= p.x && c.x + c.w <= p.x + p.w)
            assertTrue(c.y >= p.y + PANEL_HEADER && c.y + c.h <= p.y + p.h)
        }
    }

    @Test fun rowsWrapAtTheColumnCount() {
        val l = UiModel.seed(modules, columns = 1)
        assertEquals(0f, l.panels[1].x, 0f)
        assertTrue(l.panels[1].y > l.panels[0].y + l.panels[0].h)
    }

    @Test fun mirrorIsItsOwnInverse() {
        val w = 800f
        val m = UiModel.mirrorX(100f, 190f, w)
        assertEquals(510f, m, 0f)
        assertEquals(100f, UiModel.mirrorX(m, 190f, w), 0f)
        assertEquals(UiModel.worldWidth(UiModel.seed(modules, 2)), 2 * PANEL_W + PANEL_GAP, 0f)
    }

    @Test fun movingSnapsToTheGrid() {
        val l = UiModel.seed(modules)
        val c0 = l.controls[0]
        val moved = UiModel.moveControl(l, c0.id, 5.3f, -2f).controls[0]
        assertEquals(0f, moved.x % SNAP, 0f)
        assertEquals(0f, moved.y % SNAP, 0f)
        assertEquals(c0.x + 4f, moved.x, 0f)
    }

    @Test fun movingAPanelMovesItsControlsOnly() {
        val l = UiModel.seed(modules)
        val moved = UiModel.movePanel(l, "draw", 40f, 20f)
        val before = l.controls.first { it.id == "draw.Spacing" }
        val after = moved.controls.first { it.id == "draw.Spacing" }
        assertEquals(before.x + 40f, after.x, 0f)
        assertEquals(before.y + 20f, after.y, 0f)
        assertEquals(l.controls.first { it.id == "cam.Torch" }, moved.controls.first { it.id == "cam.Torch" })
        assertEquals(l.panels[1], moved.panels[1])
    }

    @Test fun resizeHasAMinimum() {
        val l = UiModel.seed(modules)
        val r = UiModel.resizeControl(l, "draw.Spacing", -500f, -500f).controls[0]
        assertEquals(40f, r.w, 0f)
        assertEquals(28f, r.h, 0f)
    }

    @Test fun cablesFollowTheJackTypes() {
        val l = UiModel.seed(modules)
        // a slider takes a gate as a number, so a toggle can drive it; the cable is stored from the source to the target
        val a = UiModel.connect(l, "draw.Spacing", "cam.Torch")
        assertNotNull(a)
        assertEquals(listOf(UiCable("cam.Torch", "draw.Spacing")), a!!.cables)
        assertNull(UiModel.connect(a, "draw.Spacing", "cam.Torch"))
        assertNull(UiModel.connect(a, "cam.Torch", "draw.Spacing"))
        assertNull(UiModel.connect(l, "draw.Spacing", "draw.Spacing"))
        assertNull(UiModel.connect(l, "draw.Spacing", "nope"))
        // a button's trigger fits a slider, but a trigger does not fit a gate
        val b = UiModel.connect(a, "draw.Save", "draw.Spacing")!!
        assertEquals(2, UiModel.cablesOf(b, "draw.Spacing").size)
        assertNull(UiModel.connect(l, "draw.Save", "cam.Torch"))
        assertEquals(1, UiModel.disconnectPort(b, "draw.Save").cables.size)
        assertEquals(0, UiModel.disconnect(a, a.cables[0]).cables.size)
    }

    @Test fun portTypesFollowTheControlKind() {
        assertEquals(dk.cbavf.studio.routing.model.JackType.Value, ControlKind.Slider.portType())
        assertEquals(dk.cbavf.studio.routing.model.JackType.Trigger, ControlKind.Button.portType())
        assertEquals(dk.cbavf.studio.routing.model.JackType.Gate, ControlKind.Toggle.portType())
        assertEquals(dk.cbavf.studio.routing.model.JackType.Gate, ControlKind.Envelope.portType())
    }

    @Test fun mergeKeepsEverythingSavedAndPlacesNewControlsInTheirPanel() {
        val fresh = UiModel.seed(modules)
        val saved = UiModel.moveControl(fresh, "draw.Spacing", 100f, 100f).let { UiModel.connect(it, "draw.Spacing", "cam.Torch")!! }
        val offered = listOf(ModuleSeed("draw", "Draw", listOf(ControlSeed("Spacing", ControlKind.Slider), ControlSeed("Angle", ControlKind.Slider))))
        val merged = UiModel.merge(saved, UiModel.seed(offered))
        // saved places survive, and so do controls the modules do not offer right now (and their cable)
        assertEquals(saved.controls.first { it.id == "draw.Spacing" }.x, merged.controls.first { it.id == "draw.Spacing" }.x, 0f)
        assertTrue(merged.controls.any { it.id == "cam.Torch" })
        assertEquals(1, merged.cables.size)
        // the new control goes below the draw panel's existing ones, and the panel grows to hold it
        val panel = merged.panels.first { it.id == "draw" }
        val angle = merged.controls.first { it.id == "draw.Angle" }
        assertTrue(angle.y >= panel.y + PANEL_HEADER)
        assertTrue(angle.y + angle.h <= panel.y + panel.h)
        assertEquals(2, merged.panels.size)
    }
}
