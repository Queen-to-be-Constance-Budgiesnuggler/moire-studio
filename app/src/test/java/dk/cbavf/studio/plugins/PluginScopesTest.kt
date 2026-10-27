package dk.cbavf.studio.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginScopesTest {
    private val red = IntArray(4 * 2) { 0xFFFF0000.toInt() }

    @Test fun theParadeHasATraceForEachChannel() {
        assertEquals(3, PluginScopes.traces(PluginScopes.RGB_PARADE, red, 4, 2, cols = 2, bins = 4).size)
    }

    @Test fun aRedPictureIsHighInRedAndLowInGreenAndBlue() {
        val (r, g, b) = PluginScopes.traces(PluginScopes.RGB_PARADE, red, 4, 2, cols = 2, bins = 4)
        // Each column holds 2 × 2 pixels; bin 3 is the top (bright), bin 0 the bottom.
        assertEquals(4, r[0 * 4 + 3])
        assertEquals(4, g[0 * 4 + 0])
        assertEquals(4, b[1 * 4 + 0])
        assertEquals(8, r.sum())
    }

    @Test fun theWaveformHasOneTrace() {
        assertEquals(1, PluginScopes.traces(PluginScopes.WAVEFORM, red, 4, 2, cols = 2, bins = 4).size)
    }

    @Test fun anUnknownScopeOrBadPictureDrawsNothing() {
        assertTrue(PluginScopes.traces("nope", red, 4, 2).isEmpty())
        assertTrue(PluginScopes.traces(null, red, 4, 2).isEmpty())
        assertTrue(PluginScopes.traces(PluginScopes.RGB_PARADE, red, 0, 2).isEmpty())
        assertTrue(PluginScopes.traces(PluginScopes.RGB_PARADE, red, 8, 8).isEmpty())
    }

    @Test fun everyScopeHasAName() {
        assertEquals("RGB parade", PluginScopes.label(PluginScopes.RGB_PARADE))
        PluginScopes.ALL.forEach { (id, _) -> assertTrue(PluginScopes.label(id)!!.isNotBlank()) }
    }
}
