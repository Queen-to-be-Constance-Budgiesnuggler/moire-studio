package dk.cbavf.studio.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcUnitsTest {
    private fun ok(s: String): CalcResult = when (val o = Calc.run(s)) {
        is CalcOutcome.Ok -> o.result
        is CalcOutcome.Err -> throw AssertionError("$s: ${o.message}")
    }

    private fun err(s: String): String = when (val o = Calc.run(s)) {
        is CalcOutcome.Ok -> throw AssertionError("$s gave ${o.result.text}, expected an error")
        is CalcOutcome.Err -> o.message
    }

    @Test fun kilometresConvertToMiles() {
        val r = ok("5 km in mi")
        assertEquals(5000 / 1609.344, r.number, 1e-9)
        assertTrue(r.text.endsWith(" mi"))
    }

    @Test fun inchesConvertToCentimetres() = assertEquals("2.54 cm", ok("1 inch in cm").text)

    @Test fun aSumKeepsTheFirstUnit() = assertEquals("5.3 km", ok("5 km + 300 m").text)

    @Test fun celsiusConvertsToFahrenheit() = assertEquals("212 °F", ok("100 °C in °F").text)

    @Test fun minusFortyIsTheSameInBothScales() = assertEquals("-40 °F", ok("-40 C in F").text)

    @Test fun degreesConvertToRadians() = assertEquals(Math.PI / 2, ok("90 deg in rad").number, 1e-12)

    @Test fun theDegreeSignIsADegree() = assertEquals("0.5 turn", ok("180° in turn").text)

    @Test fun oneOverATimeIsAFrequency() = assertEquals("0.5 Hz", ok("1/2 s").text)

    @Test fun beatsPerMinuteConvertToHertz() = assertEquals("2 Hz", ok("120 bpm in Hz").text)

    @Test fun binaryDataSizesUse1024() = assertEquals("1024 B", ok("1 KiB in B").text)

    @Test fun roundingWorksInTheShownUnit() = assertEquals("3 km", ok("round(2.6 km)").text)

    @Test fun differentKindsDoNotAdd() = assertEquals("Can't add a mass to a length", err("5 km + 3 kg"))

    @Test fun aLengthDoesNotTurnIntoAMass() = assertTrue(err("5 km in kg").startsWith("Can't turn a length into kg"))

    @Test fun aPlainNumberHasNoUnitToConvert() = assertTrue(err("5 in km").startsWith("A plain number has no unit"))

    @Test fun temperaturesAreOnlyConverted() = assertTrue(err("10 °C + 5 °C").startsWith("Temperatures can only be converted"))

    @Test fun aValueTakesOneUnit() = assertTrue(err("(5 km) m").startsWith("That already has a unit"))

    @Test fun unitsAreFoundByAlias() {
        assertEquals("°C", Units.find("degC")?.name)
        assertEquals("inch", Units.find("inches")?.name)
        assertNull(Units.find("in"))
    }

    @Test fun everyDimensionButPlainHasABaseUnit() {
        Dimension.entries.filter { it != Dimension.None }.forEach { d -> assertEquals(d, Units.base(d)?.dim) }
    }
}
