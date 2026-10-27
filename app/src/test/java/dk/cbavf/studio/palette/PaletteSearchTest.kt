package dk.cbavf.studio.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaletteSearchTest {
    private val rooms = listOf("Studio", "Library", "Compose", "Node Lab", "Draw", "Animate", "FFT", "Glitch", "Slit-scan")

    @Test fun anExactNameWins() = assertEquals("Draw", PaletteSearch.match("draw", rooms))

    @Test fun aPrefixBeatsAContainedWord() {
        // "s" starts Studio and Slit-scan; Studio comes first
        assertEquals("Studio", PaletteSearch.match("s", rooms))
        assertEquals("Slit-scan", PaletteSearch.match("sl", rooms))
    }

    @Test fun aContainedWordIsFoundWhenNothingStartsWithIt() {
        assertEquals("Node Lab", PaletteSearch.match("lab", rooms))
        assertEquals("Compose", PaletteSearch.match("pose", rooms))
    }

    @Test fun spacesAndCaseAreIgnored() = assertEquals("Node Lab", PaletteSearch.match("  NODE ", rooms))

    @Test fun nothingMatchesABlankOrUnknownQuery() {
        assertNull(PaletteSearch.match("", rooms))
        assertNull(PaletteSearch.match("   ", rooms))
        assertNull(PaletteSearch.match("zzz", rooms))
    }
}
