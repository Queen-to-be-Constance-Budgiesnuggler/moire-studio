package dk.cbavf.studio.draw

import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawStateTest {
    private fun DrawState.drawLine(from: Pt, to: Pt) {
        tool = Tool.Pen
        draftStart(from)
        for (i in 1..10) draftMove(from + (to - from) * (i / 10f))
        penUp()
    }

    private val DrawState.strokes get() = layers.filter { it.content is Stroke }

    @Test
    fun penAddsSelectedStrokeAndUndoRemovesIt() {
        val s = DrawState()
        val before = s.layers.size
        s.drawLine(Pt(0f, 0f), Pt(100f, 0f))
        assertEquals(before + 1, s.layers.size)
        assertEquals(setOf(s.layers.last().id), s.selection)
        s.undo()
        assertEquals(before, s.layers.size)
    }

    @Test
    fun tapWithoutDragAddsNoStroke() {
        val s = DrawState()
        val before = s.layers.size
        s.draftStart(Pt(5f, 5f))
        s.penUp()
        assertEquals(before, s.layers.size)
    }

    @Test
    fun lassoSelectsOnlyStrokesInside() {
        val s = DrawState()
        s.drawLine(Pt(0f, 0f), Pt(50f, 0f))
        s.drawLine(Pt(0f, 500f), Pt(50f, 500f))
        val (near, far) = s.strokes
        s.tool = Tool.Lasso
        listOf(Pt(-10f, -10f), Pt(60f, -10f), Pt(60f, 10f), Pt(-10f, 10f)).forEachIndexed { i, p ->
            if (i == 0) s.draftStart(p) else s.draftMove(p)
        }
        s.lassoUp()
        assertEquals(setOf(near.id), s.selection)
        assertFalse(far.id in s.selection)
        assertEquals(Tool.Move, s.tool)
    }

    @Test
    fun duplicateOffsetsCopyAndSelectsIt() {
        val s = DrawState()
        s.drawLine(Pt(0f, 0f), Pt(100f, 0f))
        val original = s.layers.last().content as Stroke
        s.duplicate()
        val copy = s.layers.last().content as Stroke
        assertEquals(2, s.strokes.size)
        assertEquals(original.transform.tx + 32f, copy.transform.tx, 1e-4f)
        assertEquals(setOf(s.layers.last().id), s.selection)
    }

    @Test
    fun groupRotationTurnsStrokesAroundCommonCentre() {
        val s = DrawState()
        s.drawLine(Pt(-10f, 0f), Pt(10f, 0f))      // centred at (0,0)
        s.drawLine(Pt(90f, 0f), Pt(110f, 0f))      // centred at (100,0)
        s.tool = Tool.Lasso
        listOf(Pt(-50f, -50f), Pt(150f, -50f), Pt(150f, 50f), Pt(-50f, 50f)).forEachIndexed { i, p ->
            if (i == 0) s.draftStart(p) else s.draftMove(p)
        }
        s.lassoUp()
        s.onTransform(Pt(0f, 0f), 1f, 180f)
        val (a, b) = s.strokes.map { it.content as Stroke }
        // Pivot (50,0): the two swap places.
        assertEquals(100f, a.transform.tx, 1e-3f)
        assertEquals(0f, b.transform.tx, 1e-3f)
    }

    @Test
    fun sliderDragIsOneUndoStep() {
        val s = DrawState()
        s.drawLine(Pt(0f, 0f), Pt(100f, 0f))
        repeat(5) { i -> s.updateSub("w") { it.copy(widthPx = 10f + i) } }
        assertTrue(s.canUndo)
        s.undo()
        val stroke = s.layers.last().content as Stroke
        assertEquals(DrawState().pen.stack[0].widthPx, stroke.stack[0].widthPx, 0f)
    }
}
