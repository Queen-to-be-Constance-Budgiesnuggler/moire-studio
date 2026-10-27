package dk.cbavf.studio.uieditor

import dk.cbavf.studio.routing.ButtonSpec
import dk.cbavf.studio.routing.KnobSpec
import dk.cbavf.studio.routing.ModuleBackSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class UiCablesTest {
    private class Fake(val kind: ControlKind, var level: Float = 0f) {
        val writes = ArrayList<Float>()
        fun live() = LiveControl(kind, { level }) { writes += it }
    }

    @Test fun aSliderFollowsItsSourceAndIsOnlyTouchedWhenTheLevelChanges() {
        val src = Fake(ControlKind.Toggle, 1f)
        val tgt = Fake(ControlKind.Slider)
        val controls = mapOf("a.On" to src.live(), "b.Amount" to tgt.live())
        val memo = HashMap<String, Float>()
        val cables = listOf(UiCable("a.On", "b.Amount"))
        UiCables.step(cables, controls, memo)
        UiCables.step(cables, controls, memo)
        assertEquals(listOf(1f), tgt.writes)
        src.level = 0f
        UiCables.step(cables, controls, memo)
        assertEquals(listOf(1f, 0f), tgt.writes)
    }

    @Test fun aButtonIsPressedOnTheRisingEdgeOnly() {
        val src = Fake(ControlKind.Toggle, 0f)
        val btn = Fake(ControlKind.Button)
        val controls = mapOf("a.On" to src.live(), "b.Go" to btn.live())
        val memo = HashMap<String, Float>()
        val cables = listOf(UiCable("a.On", "b.Go"))
        UiCables.step(cables, controls, memo)
        assertEquals(0, btn.writes.size)
        src.level = 1f
        UiCables.step(cables, controls, memo)
        UiCables.step(cables, controls, memo)
        assertEquals(1, btn.writes.size)
        src.level = 0f
        UiCables.step(cables, controls, memo)
        src.level = 1f
        UiCables.step(cables, controls, memo)
        assertEquals(2, btn.writes.size)
    }

    @Test fun cablesToControlsThatAreNotOfferedAreIgnored() {
        val tgt = Fake(ControlKind.Slider)
        UiCables.step(listOf(UiCable("gone.X", "b.Amount"), UiCable("a.On", "gone.Y")), mapOf("b.Amount" to tgt.live()), HashMap())
        assertEquals(0, tgt.writes.size)
    }

    @Test fun controlsComeFromTheModuleBacks() {
        var value = 25f
        var on = false
        val spec = ModuleBackSpec(
            "Draw", "Draw", null,
            knobs = listOf(KnobSpec("Size", value, 0f..100f, whole = true, onChange = { value = it })),
            buttons = listOf(ButtonSpec("Sort: Name ▸", on = on, onClick = { on = !on }), ButtonSpec("Save", onClick = {})),
        )
        val c = liveControls(listOf(spec))
        assertEquals(setOf("Draw.Size", "Draw.Sort", "Draw.Save"), c.keys)
        assertEquals(0.25f, c.getValue("Draw.Size").read(), 1e-6f)
        c.getValue("Draw.Size").write(0.5f)
        assertEquals(50f, value, 1e-6f)
        c.getValue("Draw.Sort").write(1f)
        assertEquals(true, on)
        assertEquals(ControlKind.Toggle, c.getValue("Draw.Sort").kind)
        assertEquals(ControlKind.Button, c.getValue("Draw.Save").kind)
    }
}
