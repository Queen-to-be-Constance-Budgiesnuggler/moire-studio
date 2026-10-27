package dk.cbavf.studio.blend

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtractMathTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(p: Int, shift: Int) = p shr shift and 0xFF

    @Test fun opaqueSubtractTakesTheSourceFromTheBackdrop() {
        val out = SubtractMath.blendPixel(argb(255, 200, 100, 50), argb(255, 50, 100, 80), 1f, reverse = false)
        assertEquals(255, ch(out, 24))
        assertEquals(150, ch(out, 16))
        assertEquals(0, ch(out, 8))   // 100 - 100
        assertEquals(0, ch(out, 0))   // 50 - 80 clamps at zero
    }

    @Test fun reverseSubtractTakesTheBackdropFromTheSource() {
        val out = SubtractMath.blendPixel(argb(255, 200, 100, 50), argb(255, 50, 100, 80), 1f, reverse = true)
        assertEquals(0, ch(out, 16))
        assertEquals(0, ch(out, 8))
        assertEquals(30, ch(out, 0))
    }

    @Test fun halfOpacityMixesTheResultWithTheBackdrop() {
        // d = 200, s = 100: B = 100; at opacity 0.5 the result is halfway between 200 and 100
        val out = SubtractMath.blendPixel(argb(255, 200, 200, 200), argb(255, 100, 100, 100), 0.5f, reverse = false)
        assertEquals(150, ch(out, 16))
    }

    @Test fun zeroOpacityAndTransparentSourceLeaveTheBackdropAlone() {
        val dst = argb(255, 10, 20, 30)
        assertEquals(dst, SubtractMath.blendPixel(dst, argb(255, 255, 255, 255), 0f, reverse = false))
        assertEquals(dst, SubtractMath.blendPixel(dst, argb(0, 255, 255, 255), 1f, reverse = false))
    }

    @Test fun overATransparentBackdropTheSourceShowsAsItIs() {
        val out = SubtractMath.blendPixel(0, argb(255, 90, 80, 70), 1f, reverse = false)
        assertEquals(argb(255, 90, 80, 70), out)
    }

    @Test fun subtractingBlackChangesNothing() {
        val dst = argb(255, 12, 99, 201)
        assertEquals(dst, SubtractMath.blendPixel(dst, argb(255, 0, 0, 0), 1f, reverse = false))
    }

    @Test fun subtractingTheSameColourGivesBlack() {
        val c = argb(255, 77, 140, 33)
        assertEquals(argb(255, 0, 0, 0), SubtractMath.blendPixel(c, c, 1f, reverse = false))
    }
}
