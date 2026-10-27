package dk.cbavf.studio.osc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OscMapTest {
    @Test fun theLastPartOfTheAddressPicksTheJack() {
        assertEquals("osc.a", OscMap.jackFor("/studio/a"))
        assertEquals("osc.b", OscMap.jackFor("/b"))
        assertEquals("osc.c", OscMap.jackFor("/fader/3"))
        assertEquals("osc.d", OscMap.jackFor("/x/y/D"))
        assertEquals("osc.a", OscMap.jackFor("/studio/a/"))
    }

    @Test fun otherAddressesFeedNothing() {
        assertNull(OscMap.jackFor("/studio/volume"))
        assertNull(OscMap.jackFor("/5"))
        assertNull(OscMap.jackFor("/"))
    }
}
