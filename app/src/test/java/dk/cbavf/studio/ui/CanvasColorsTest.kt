package dk.cbavf.studio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CanvasColorsTest {
    @Test fun theStudioThemeKeepsTheClassicLook() {
        assertEquals(CanvasTheme.Classic, CanvasTheme.of(StudioTheme.Studio))
    }

    @Test fun theCanvasIsTheThemeBackground() {
        assertEquals(StudioTheme.Nord.p.bg, CanvasTheme.of(StudioTheme.Nord).canvas)
    }

    @Test fun theGridIsAFaintLineOfTheTextColour() {
        val p = StudioTheme.Dracula.p
        val grid = CanvasTheme.of(StudioTheme.Dracula).grid
        assertEquals(p.text and 0xFFFFFF, grid and 0xFFFFFF)
        assertEquals(0x22L, grid shr 24 and 0xFF)
    }

    @Test fun nodesSitOnTheRaisedSurface() {
        assertEquals(StudioTheme.GruvboxDark.p.raised, CanvasTheme.of(StudioTheme.GruvboxDark).node)
    }

    @Test fun selectionUsesTheAccent() {
        val cc = CanvasTheme.of(StudioTheme.TokyoNight)
        assertEquals(StudioTheme.TokyoNight.p.accent, cc.select)
        assertEquals(StudioTheme.TokyoNight.p.accent and 0xFFFFFF, cc.selectFill and 0xFFFFFF)
    }

    @Test fun rackPanelsUseTheThemeSurfaceAndText() {
        val p = StudioTheme.SolarizedDark.p
        val cc = CanvasTheme.of(StudioTheme.SolarizedDark)
        assertEquals(p.surface, cc.panel)
        assertEquals(p.text, cc.silk)
    }

    @Test fun differentThemesGiveDifferentCanvases() {
        assertNotEquals(CanvasTheme.of(StudioTheme.Monokai).canvas, CanvasTheme.of(StudioTheme.OneDark).canvas)
    }

    @Test fun nodeTextReadsOnNodesInEveryTheme() {
        StudioTheme.entries.forEach { t ->
            val cc = CanvasTheme.of(t)
            assertTrue("${t.label}: node text", contrast(cc.nodeText, cc.node) > 0.3f)
            assertTrue("${t.label}: field text", contrast(cc.fieldText, cc.field) > 0.3f)
            assertTrue("${t.label}: panel text", contrast(cc.silk, cc.panel) > 0.3f)
        }
    }

    @Test fun jackHolesAreDarkerThanTheirPanelInEveryTheme() {
        StudioTheme.entries.forEach { t ->
            val cc = CanvasTheme.of(t)
            assertTrue(t.label, StudioTheme.luminance(cc.hole) < StudioTheme.luminance(cc.panel))
        }
    }

    @Test fun mixAtZeroAndOneGivesTheEnds() {
        assertEquals(0xFF102030, CanvasTheme.mix(0xFF102030, 0xFFF0E0D0, 0f))
        assertEquals(0xFFF0E0D0, CanvasTheme.mix(0xFF102030, 0xFFF0E0D0, 1f))
    }

    @Test fun mixHalfwayIsTheMiddleOfEachChannel() {
        assertEquals(0xFF808080, CanvasTheme.mix(0xFF000000, 0xFFFFFFFF, 0.5f))
    }

    @Test fun withAlphaKeepsTheColour() {
        assertEquals(0x33ABCDEFL,CanvasTheme.withAlpha(0xFFABCDEF, 0x33))
    }

    private fun contrast(a: Long, b: Long) = abs(StudioTheme.luminance(a) - StudioTheme.luminance(b))
}
