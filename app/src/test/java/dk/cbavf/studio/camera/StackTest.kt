package dk.cbavf.studio.camera

import dk.cbavf.studio.camera.model.CaptureInfo
import dk.cbavf.studio.camera.model.Stack
import dk.cbavf.studio.camera.model.StackMode
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test

class StackTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun additiveSumsTimesGainAndClips() {
        val rows = listOf(intArrayOf(rgb(100, 200, 10)), intArrayOf(rgb(100, 200, 10)))
        val out = IntArray(1)
        Stack.combineRow(rows, StackMode.Additive(gain = 1f), out)
        assertEquals(rgb(200, 255, 20), out[0])
        Stack.combineRow(rows, StackMode.Additive(gain = 0.5f), out)
        assertEquals(rgb(100, 200, 10), out[0])
    }

    @Test
    fun meanAverages() {
        val out = IntArray(1)
        Stack.combineRow(listOf(intArrayOf(rgb(0, 0, 0)), intArrayOf(rgb(255, 100, 51))), StackMode.Mean, out)
        assertEquals(rgb(128, 50, 26), out[0])
    }

    @Test
    fun interlaceFollowsTheRollingShutterFormula() {
        // row r sees frame floor((t0 + r·t_row) / T_frame): 10 rows per frame, 3 frames, cycling.
        val mode = StackMode.Interlace(rowsPerFrame = 10f)
        assertEquals(listOf(0, 0, 1, 2, 0), listOf(0, 9, 10, 25, 30).map { Stack.interlaceFrame(it, mode, 3) })
        assertEquals(1, Stack.interlaceFrame(0, mode.copy(phaseRows = 10f), 3))
    }

    @Test
    fun shutterSpeedsParseTheWaysPhotographersWriteThem() {
        assertEquals(8_000_000.0, CaptureInfo.parseShutterNs("1/125")!!, 1.0)
        assertEquals(8_000_000.0, CaptureInfo.parseShutterNs("0,008")!!, 1.0)
        assertEquals(8_000_000.0, CaptureInfo.parseShutterNs("8ms")!!, 1.0)
        assertEquals(2e9, CaptureInfo.parseShutterNs("2s")!!, 1.0)
        assertEquals(null, CaptureInfo.parseShutterNs("fast"))
        assertEquals(null, CaptureInfo.parseShutterNs("1/0"))
    }

    @Test
    fun clearAllEmptiesAnimateAndUndoBringsItBack() {
        val a = dk.cbavf.studio.animate.AnimateState()
        repeat(3) { a.insert(dk.cbavf.studio.library.CanvasDoc(10, 10, emptyList())) }
        a.clearAll()
        assertEquals(0, a.frames.size)
        a.undo()
        assertEquals(3, a.frames.size)
    }

    @Test
    fun captureSettingsRoundTripInRecipes() {
        val info = CaptureInfo("0", exposureNs = 8_000_000, iso = 200, aperture = 2.4f, burst = 4, stack = StackMode.Interlace(32f))
        val json = ProjectStore.json.encodeToString(Recipe.serializer(), Recipe(capture = info))
        assertEquals(info, ProjectStore.json.decodeFromString(Recipe.serializer(), json).capture)
        assertEquals("1/125 · ISO 200 · f/2.4 · 4× interlace 32 rows", info.summary)
    }
}
