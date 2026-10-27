package dk.cbavf.studio.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLayoutMathTest {
    @Test fun portraitStacks() {
        assertEquals(LayoutMode.Stacked, RoomLayoutMath.mode(411f, 891f))
    }

    @Test fun wideAndLowGoesSideBySide() {
        assertEquals(LayoutMode.SideBySide, RoomLayoutMath.mode(891f, 411f))
    }

    @Test fun aSquareishOrNarrowWindowStaysStacked() {
        assertEquals(LayoutMode.Stacked, RoomLayoutMath.mode(500f, 500f))
        // wider than tall, but too narrow for a panel beside a useful picture
        assertEquals(LayoutMode.Stacked, RoomLayoutMath.mode(540f, 400f))
    }

    @Test fun noRequestGivesTheDefaultWidth() {
        assertEquals(RoomLayoutMath.DEFAULT_PANEL, RoomLayoutMath.panelWidth(0f, 900f), 1e-4f)
    }

    @Test fun theWidthIsKeptBetweenTheLimits() {
        assertEquals(RoomLayoutMath.MIN_PANEL, RoomLayoutMath.panelWidth(100f, 900f), 1e-4f)
        assertEquals(RoomLayoutMath.MAX_PANEL, RoomLayoutMath.panelWidth(2000f, 2000f), 1e-4f)
    }

    @Test fun thePictureKeepsAtLeastFortyPercent() {
        // a 700 dp window: 60 % is 420
        assertEquals(420f, RoomLayoutMath.panelWidth(560f, 700f), 1e-4f)
    }

    @Test fun aTinyWindowStillGetsTheMinimum() {
        assertEquals(RoomLayoutMath.MIN_PANEL, RoomLayoutMath.panelWidth(400f, 300f), 1e-4f)
    }

    @Test fun aPhonePageInPortraitFillsTheWindow() {
        assertNull(RoomLayoutMath.pageWidth(411f, 891f))
    }

    @Test fun aWidePageIsKeptToItsReadableWidth() {
        assertEquals(RoomLayoutMath.PAGE_MAX, RoomLayoutMath.pageWidth(1200f, 1600f)!!, 1e-4f)
        assertEquals(RoomLayoutMath.PAGE_MAX, RoomLayoutMath.pageWidth(891f, 411f)!!, 1e-4f)
    }

    @Test fun landscapeAddsRoomForTheRail() {
        // A phone held sideways: 760 + 200 is wider than the window, so the page with its rail fills it.
        assertNull(RoomLayoutMath.pageWidth(891f, 411f, landscapeExtraDp = RoomLayoutMath.RAIL))
        assertEquals(960f, RoomLayoutMath.pageWidth(1280f, 800f, landscapeExtraDp = RoomLayoutMath.RAIL)!!, 1e-4f)
    }

    @Test fun portraitGetsNoRailWidth() {
        assertEquals(RoomLayoutMath.PAGE_MAX, RoomLayoutMath.pageWidth(800f, 1280f, landscapeExtraDp = RoomLayoutMath.RAIL)!!, 1e-4f)
    }

    @Test fun theRailOnlyShowsInAWideLowWindow() {
        assertTrue(RoomLayoutMath.pageRail(891f, 411f))
        assertFalse(RoomLayoutMath.pageRail(411f, 891f))
        assertFalse(RoomLayoutMath.pageRail(540f, 400f))
    }
}
