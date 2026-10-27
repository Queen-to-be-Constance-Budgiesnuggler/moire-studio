package dk.cbavf.studio.glitch

import dk.cbavf.studio.glitch.model.Glitch
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.glitch.model.OrientationField
import dk.cbavf.studio.glitch.model.PixelSort
import dk.cbavf.studio.glitch.model.Pixels
import dk.cbavf.studio.glitch.model.SortField
import dk.cbavf.studio.glitch.model.SortKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PixelSortTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun greys(vararg v: Int) = IntArray(v.size) { grey(v[it]) }

    @Test
    fun angleZeroSortsEachRowDarkToBright() {
        val img = greys(200, 50, 120, /* row 2 */ 30, 250, 10)
        val out = PixelSort.sort(img, 3, 2, OrientationField.constant(0f), SortKey.Brightness, 0f, 1f)
        assertArrayEquals(greys(50, 120, 200, 10, 30, 250), out)
    }

    @Test
    fun angle180SortsEachRowBrightToDark() {
        val img = greys(200, 50, 120)
        val out = PixelSort.sort(img, 3, 1, OrientationField.constant(180f), SortKey.Brightness, 0f, 1f)
        assertArrayEquals(greys(200, 120, 50), out)
    }

    @Test
    fun angle90SortsEachColumnTopToBottom() {
        // 2 columns × 3 rows.
        val img = greys(90, 1, 30, 3, 60, 2)
        val out = PixelSort.sort(img, 2, 3, OrientationField.constant(90f), SortKey.Brightness, 0f, 1f)
        assertArrayEquals(greys(30, 1, 60, 2, 90, 3), out)
    }

    @Test
    fun pixelsOutsideTheRangeStayAndBreakTheRuns() {
        // Range 64..204: 20 is too dark, so it stays and splits the row into two runs.
        val img = greys(180, 100, 20, 150, 90)
        val out = PixelSort.sort(img, 5, 1, OrientationField.constant(0f), SortKey.Brightness, 0.25f, 0.8f)
        assertArrayEquals(greys(100, 180, 20, 90, 150), out)
    }

    @Test
    fun aReversedRangeWorksTheSame() {
        val img = greys(180, 100, 20, 150, 90)
        val a = PixelSort.sort(img, 5, 1, OrientationField.constant(0f), SortKey.Brightness, 0.25f, 0.8f)
        val b = PixelSort.sort(img, 5, 1, OrientationField.constant(0f), SortKey.Brightness, 0.8f, 0.25f)
        assertArrayEquals(a, b)
    }

    @Test
    fun hueSortsRedGreenBlue() {
        val img = intArrayOf(rgb(0, 0, 255), rgb(255, 0, 0), rgb(0, 255, 0))
        val out = PixelSort.sort(img, 3, 1, OrientationField.constant(0f), SortKey.Hue, 0f, 1f)
        assertArrayEquals(intArrayOf(rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255)), out)
    }

    @Test
    fun anyAngleOnlyMovesPixelsAround() {
        val rnd = Random(5)
        val w = 37
        val h = 23
        val img = IntArray(w * h) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        for (angle in listOf(0f, 30f, 45f, 110f, 200f, 333f)) {
            val out = PixelSort.sort(img, w, h, OrientationField.constant(angle), SortKey.Brightness, 0.1f, 0.9f)
            assertArrayEquals("angle $angle", img.sortedArray(), out.sortedArray())
        }
    }

    @Test
    fun edgeFieldRunsAlongTheEdges() {
        // Brightness grows to the right, so the edges (lines of equal brightness) are vertical.
        val w = 64
        val img = IntArray(w * w) { grey((it % w) * 4) }
        val field = OrientationField.edges(img, w, w)
        val i = field.index(32, 32)
        assertEquals(0f, field.dx[i], 1e-3f)
        assertEquals(1f, abs(field.dy[i]), 1e-3f)
    }

    @Test
    fun aFlatImageFallsBackToTheAngle() {
        val img = IntArray(16 * 16) { grey(128) }
        val field = OrientationField.edges(img, 16, 16, degrees = 90f)
        val i = field.index(8, 8)
        assertEquals(0f, field.dx[i], 1e-4f)
        assertEquals(1f, field.dy[i], 1e-4f)
    }

    @Test
    fun sortingAlongEdgesLeavesAnImageThatIsConstantAlongThem() {
        // Darker to the right: a row sort changes it, a sort along its vertical edges does not.
        val w = 32
        val img = IntArray(w * w) { grey(255 - (it % w) * 8) }
        val rows = PixelSort.sort(img, w, w, OrientationField.constant(0f), SortKey.Brightness, 0f, 1f)
        val edges = PixelSort.sort(img, w, w, OrientationField.edges(img, w, w), SortKey.Brightness, 0f, 1f)
        assertTrue(!rows.contentEquals(img))
        assertArrayEquals(img, edges)
    }

    @Test
    fun boxBlurKeepsAConstant() {
        val v = FloatArray(5 * 4) { 3f }
        OrientationField.boxBlur(v, 5, 4, 2)
        v.forEach { assertEquals(3f, it, 1e-5f) }
    }

    @Test
    fun pixelStagesDoNothingWhenOff() {
        val px = Pixels(greys(3, 2, 1), 3, 1)
        assertSame(px, Glitch.pixelStages(px, GlitchDoc(), palette = greys(200)))
    }

    @Test
    fun pixelStagesSortWhenTheDocAsks() {
        val px = Pixels(greys(3, 2, 1), 3, 1)
        val out = Glitch.pixelStages(px, GlitchDoc(sortField = SortField.Angle, sortLow = 0f, sortHigh = 1f), palette = null)
        assertArrayEquals(greys(1, 2, 3), out.argb)
    }

    @Test
    fun theSummaryNamesTheSort() {
        assertEquals("no glitch", GlitchDoc().summary)
        assertTrue(GlitchDoc(sortField = SortField.Angle, sortAngle = 90f).summary.contains("sort brightness at 90°"))
        assertTrue(GlitchDoc(sortField = SortField.Edges, sortKey = SortKey.Hue).summary.contains("sort hue along edges"))
    }
}
