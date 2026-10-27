package dk.cbavf.studio.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {
    private fun ok(s: String): CalcResult = when (val o = Calc.run(s)) {
        is CalcOutcome.Ok -> o.result
        is CalcOutcome.Err -> throw AssertionError("$s: ${o.message}")
    }

    private fun text(s: String) = ok(s).text

    private fun err(s: String): String = when (val o = Calc.run(s)) {
        is CalcOutcome.Ok -> throw AssertionError("$s gave ${o.result.text}, expected an error")
        is CalcOutcome.Err -> o.message
    }

    @Test fun multiplicationComesBeforeAddition() = assertEquals("14", text("2 + 3 * 4"))

    @Test fun parenthesesGroupFirst() = assertEquals("20", text("(2 + 3) * 4"))

    @Test fun powersRunRightToLeft() = assertEquals("512", text("2^3^2"))

    @Test fun unaryMinusBindsLooserThanPower() = assertEquals("-4", text("-2^2"))

    @Test fun decimalsAreShownWithoutFloatNoise() = assertEquals("0.3", text("0.1 + 0.2"))

    @Test fun exponentNotationIsRead() {
        assertEquals("2000", text("2e3"))
        assertEquals("0.003", text("1.5e-3 * 2"))
    }

    @Test fun bigWholeNumbersKeepEveryDigit() = assertEquals("123456789012345", text("123456789012345 + 0"))

    @Test fun veryBigNumbersUseAPowerOfTen() = assertEquals("1e20", text("10^20"))

    @Test fun typographicSignsWork() {
        assertEquals("12", text("3 × 4"))
        assertEquals("2", text("8 ÷ 4"))
        assertEquals("1", text("3 − 2"))
    }

    @Test fun piIsShownToTwelveDigits() = assertEquals("3.14159265359", text("pi"))

    @Test fun functionsTakeArguments() {
        assertEquals("4", text("sqrt(16)"))
        assertEquals("5", text("max(1, 5, 3)"))
        assertEquals("3", text("log(8, 2)"))
    }

    @Test fun sineTakesDegrees() = assertEquals("0.5", text("sin(30 deg)"))

    @Test fun arcsineGivesAnAngle() = assertEquals("90 deg", text("asin(1) in deg"))

    @Test fun divisionByZeroIsAnError() = assertEquals("Division by zero", err("1 / 0"))

    @Test fun squareRootOfANegativeIsAnError() = assertEquals("Not a real number", err("sqrt(-1)"))

    @Test fun unknownNamesAreNamed() = assertEquals("Unknown name: foo", err("foo + 1"))

    @Test fun aNameAfterANumberMustBeAUnit() = assertTrue(err("2 x").startsWith("Unknown unit: x"))

    @Test fun anOpenParenthesisMustClose() = assertTrue(err("(1 + 2").startsWith("Expected \")\""))

    @Test fun anExtraClosingParenthesisIsSaid() = assertEquals("One \")\" too many", err("1 + 2)"))

    @Test fun anEmptyLineAsksForASum() = assertEquals("Type a sum, like 2 + 2", err("   "))

    @Test fun aLineThatStopsTooSoonIsAnError() = assertEquals("The sum stops too soon", err("1 +"))

    @Test fun bitwiseOperatorsWorkOnWholeNumbers() {
        assertEquals("10", text("0xff & 0b1010"))
        assertEquals("7", text("6 | 1"))
        assertEquals("16", text("1 << 4"))
        assertEquals("2", text("8 >> 2"))
    }

    @Test fun bitwiseOperatorsRefuseFractions() = assertEquals("& takes whole numbers", err("1.5 & 1"))

    @Test fun builtInNamesCannotBeSet() = assertTrue(err("pi = 3").startsWith("pi is a built-in name"))

    @Test fun anAssignmentNamesItsVariable() {
        val r = ok("x = 3 * 4")
        assertEquals("x", r.assigned)
        assertEquals("x = 12", r.text)
    }

    @Test fun variablesAreRead() {
        val out = Calc.run("x * 2", mapOf("x" to Quantity(21.0)))
        assertEquals("42", (out as CalcOutcome.Ok).result.text)
    }
}
