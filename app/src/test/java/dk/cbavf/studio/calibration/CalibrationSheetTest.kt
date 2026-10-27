package dk.cbavf.studio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSheetTest {
    private fun count(s: String, what: String) = s.split(what).size - 1

    @Test fun numbersLoseTheirTrailingZeros() {
        assertEquals("2.5", CalibrationSheet.fmt(2.5))
        assertEquals("4", CalibrationSheet.fmt(4.0))
        assertEquals("0.125", CalibrationSheet.fmt(0.125))
        assertEquals("0", CalibrationSheet.fmt(0.0))
    }

    @Test fun theDocumentIsInMillimetres() {
        val s = CalibrationSheet.svg(100.0, 60.0, listOf(2.0, 4.0, 5.0))
        val lines = s.split("\n")
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100mm\" height=\"60mm\" viewBox=\"0 0 100 60\">", lines.first())
        assertEquals("</svg>", lines.last())
        assertTrue(s.contains("<rect x=\"0\" y=\"0\" width=\"100\" height=\"60\" fill=\"#fff\"/>"))
    }

    @Test fun eachBandHasFloorWidthOverPitchBars() {
        val s = CalibrationSheet.svg(100.0, 60.0, listOf(2.0, 4.0, 5.0))
        // background 1, then 50 + 25 + 20 black bars
        assertEquals(1 + 50 + 25 + 20, count(s, "<rect "))
        assertEquals(95, count(s, "fill=\"#000\""))
    }

    @Test fun barsSitOnThePitchGridInTheirBand() {
        val s = CalibrationSheet.svg(100.0, 60.0, listOf(2.0, 4.0, 5.0))
        // band 1 (pitch 4) starts at y = 20, its third bar at x = 8 with width 2 and height 20
        assertTrue(s.contains("<rect x=\"8\" y=\"20\" width=\"2\" height=\"20\" fill=\"#000\"/>"))
        // band 2 (pitch 5), the last bar at x = 95, y = 40, width 2.5
        assertTrue(s.contains("<rect x=\"95\" y=\"40\" width=\"2.5\" height=\"20\" fill=\"#000\"/>"))
    }

    @Test fun eachBandIsLabelledWithItsPitch() {
        val s = CalibrationSheet.svg(100.0, 60.0, listOf(2.0, 4.0, 5.0))
        assertTrue(s.contains("<text x=\"1\" y=\"4\" font-size=\"3\" fill=\"#f00\">2 mm</text>"))
        assertTrue(s.contains("<text x=\"1\" y=\"24\" font-size=\"3\" fill=\"#f00\">4 mm</text>"))
        assertTrue(s.contains("<text x=\"1\" y=\"44\" font-size=\"3\" fill=\"#f00\">5 mm</text>"))
    }

    @Test fun noPitchesIsJustTheBackground() {
        val s = CalibrationSheet.svg(50.0, 50.0, emptyList())
        assertEquals(1, count(s, "<rect "))
        assertEquals(0, count(s, "<text "))
    }

    @Test fun aZeroPitchDrawsNoBars() {
        val s = CalibrationSheet.svg(50.0, 50.0, listOf(0.0))
        assertEquals(1, count(s, "<rect "))
    }
}
