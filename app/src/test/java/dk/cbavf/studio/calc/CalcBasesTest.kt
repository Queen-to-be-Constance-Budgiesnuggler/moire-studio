package dk.cbavf.studio.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcBasesTest {
    private fun text(s: String): String = when (val o = Calc.run(s)) {
        is CalcOutcome.Ok -> o.result.text
        is CalcOutcome.Err -> throw AssertionError("$s: ${o.message}")
    }

    private fun err(s: String): String = when (val o = Calc.run(s)) {
        is CalcOutcome.Ok -> throw AssertionError("$s gave ${o.result.text}, expected an error")
        is CalcOutcome.Err -> o.message
    }

    @Test fun decimalShowsInHex() = assertEquals("0xff", text("255 in hex"))

    @Test fun decimalShowsInBinary() = assertEquals("0b11111111", text("255 in bin"))

    @Test fun decimalShowsInOctal() = assertEquals("0o10", text("8 in oct"))

    @Test fun hexShowsInDecimal() = assertEquals("31", text("0x1F in dec"))

    @Test fun negativeNumbersKeepTheirSignInFront() = assertEquals("-0xff", text("-255 in hex"))

    @Test fun underscoresSeparateDigits() = assertEquals("240", text("0b1111_0000"))

    @Test fun aDigitOutsideTheBaseIsAnError() = assertTrue(err("0b102").contains("not a whole number in base 2"))

    @Test fun fractionsAreNotShownInHex() = assertEquals("Only whole numbers can be shown in hex", err("2.5 in hex"))

    @Test fun valuesWithAUnitAreNotShownInHex() = assertEquals("Only plain numbers can be shown in hex", err("5 km in hex"))

    @Test fun aWholeNumberIsWrittenInEveryBase() {
        assertEquals(
            listOf(NumberBase.Dec to "10", NumberBase.Hex to "0xa", NumberBase.Bin to "0b1010", NumberBase.Oct to "0o12"),
            NumberBase.all(10.0),
        )
    }

    @Test fun aFractionHasNoBases() = assertNull(NumberBase.all(1.5))

    @Test fun numbersPastExactDoublesAreNotWhole() = assertNull(NumberBase.whole(Math.pow(2.0, 60.0)))

    @Test fun parsingRefusesForeignDigits() = assertNull(NumberBase.parse("zz", 16))
}
