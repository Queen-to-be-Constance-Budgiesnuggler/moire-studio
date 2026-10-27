package dk.cbavf.studio.channels

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class ChannelPlanesTest {
    private val sample = intArrayOf(0xFF804020.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF336699.toInt(), 0x80FF0000.toInt())

    private fun near(a: Int, b: Int, tol: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val d = abs((a shr shift and 0xFF) - (b shr shift and 0xFF))
            assertEquals("channel at shift $shift of ${Integer.toHexString(a)} vs ${Integer.toHexString(b)}", true, d <= tol)
        }
    }

    @Test fun aRedPlaneIsTheRedLevelAsGrey() {
        val p = ChannelPlanes.split(ColorSpace.RGBA, 0, sample)
        assertEquals(0xFF808080.toInt(), p[0])
        assertEquals(0xFFFFFFFF.toInt(), p[1])
        assertEquals(0xFF000000.toInt(), p[2])
    }

    @Test fun theAlphaPlaneShowsTransparency() {
        val p = ChannelPlanes.split(ColorSpace.RGBA, 3, sample)
        assertEquals(0xFFFFFFFF.toInt(), p[0])
        assertEquals(0xFF808080.toInt(), p[4])
    }

    @Test fun splitThenMergeRgbaGivesThePictureBack() {
        val planes = (0..3).map { ChannelPlanes.split(ColorSpace.RGBA, it, sample) }
        val back = ChannelPlanes.merge(ColorSpace.RGBA, planes, sample.size)
        for (i in sample.indices) near(back[i], sample[i], 1)
    }

    @Test fun splitThenMergeCmykAndLabComeBackClose() {
        for (space in listOf(ColorSpace.CMYK, ColorSpace.LAB)) {
            val planes = space.channelNames.indices.map { ChannelPlanes.split(space, it, sample) }
            val back = ChannelPlanes.merge(space, planes, sample.size)
            // opaque pixels only: the alpha is not part of these spaces
            for (i in 0..3) near(back[i], sample[i], 3)
        }
    }

    @Test fun aMissingPlaneTakesItsNeutralLevel() {
        // only red given: green and blue are zero and alpha stays opaque
        val red = ChannelPlanes.split(ColorSpace.RGBA, 0, sample)
        val merged = ChannelPlanes.merge(ColorSpace.RGBA, listOf(red, null, null, null), sample.size)
        assertEquals(0xFF800000.toInt(), merged[0])
    }

    @Test fun neutralLevels() {
        assertEquals(1f, ChannelPlanes.neutral(ColorSpace.RGBA, 3), 0f)
        assertEquals(0f, ChannelPlanes.neutral(ColorSpace.RGBA, 0), 0f)
        assertEquals(0f, ChannelPlanes.neutral(ColorSpace.LAB, 0), 0f)
        assertEquals(128f / 255f, ChannelPlanes.neutral(ColorSpace.LAB, 1), 1e-6f)
        assertEquals(0f, ChannelPlanes.neutral(ColorSpace.CMYK, 3), 0f)
    }
}
