package dk.cbavf.studio.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChannelSetupTest {
    private val px = intArrayOf(0xFF804020.toInt(), 0xFFFFFFFF.toInt(), 0x80336699.toInt())

    private fun near(a: Int, b: Int, tol: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            assertTrue("shift $shift: ${Integer.toHexString(a)} vs ${Integer.toHexString(b)}", abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= tol)
        }
    }

    @Test fun aNeutralSetupReturnsThePicturesAsItIs() {
        assertSame(px, ChannelSetup().apply(px))
        assertTrue(ChannelSetup().isNeutral)
    }

    @Test fun halvingTheRedGainHalvesRed() {
        val out = ChannelSetup(gains = listOf(0.5f, 1f, 1f, 1f)).apply(px)
        near(out[0], 0xFF404020.toInt(), 1)
        near(out[1], 0xFF80FFFF.toInt(), 1)
    }

    @Test fun anOffsetAddsAndClamps() {
        val out = ChannelSetup(offsets = listOf(0f, 0f, 0.5f, 0f)).apply(px)
        // blue 0x20 (0.125) + 0.5 = 0.625 ~ 0xA0; pure white stays at the top
        near(out[0], 0xFF8040A0.toInt(), 1)
        near(out[1], 0xFFFFFFFF.toInt(), 0)
    }

    @Test fun aMutedChannelGoesToItsNeutralLevel() {
        val out = ChannelSetup(muted = setOf(1)).apply(px)
        near(out[0], 0xFF800020.toInt(), 1)   // green muted to 0
        near(out[1], 0xFFFF00FF.toInt(), 1)
    }

    @Test fun alphaIsAChannelInRgbaAndKeptInTheOthers() {
        val rgba = ChannelSetup(gains = listOf(1f, 1f, 1f, 0.5f)).apply(px)
        near(rgba[0], 0x7F804020.toInt(), 1)       // alpha halved: 1.0 -> 0.5
        val lab = ChannelSetup(space = ColorSpace.LAB, gains = listOf(0.5f, 1f, 1f, 1f)).apply(px)
        assertEquals(0x80, lab[2] ushr 24)         // a Lab setup leaves alpha alone
    }

    @Test fun cmykGainOnBlackDarkensOrLightens() {
        val white = intArrayOf(0xFFFFFFFF.toInt())
        // white has no ink; raising the K offset adds black
        val out = ChannelSetup(space = ColorSpace.CMYK, offsets = listOf(0f, 0f, 0f, 0.5f)).apply(white)
        assertTrue((out[0] and 0xFF) < 0xC0)
    }
}
