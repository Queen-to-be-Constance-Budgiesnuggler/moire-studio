package dk.cbavf.studio.calc

import dk.cbavf.studio.routing.model.CALC_RESULT
import dk.cbavf.studio.routing.model.Cable
import dk.cbavf.studio.routing.model.RoutingDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcBookTest {
    private fun CalcBook.then(input: String): CalcBook = submit(input).first

    private fun lastText(b: CalcBook) = b.history.first().text

    @Test fun historyIsNewestFirst() {
        val b = CalcBook().then("1 + 1").then("2 + 2")
        assertEquals(listOf("2 + 2", "1 + 1"), b.history.map { it.input })
    }

    @Test fun ansIsTheLastResult() = assertEquals("43", lastText(CalcBook().then("6 * 7").then("ans + 1")))

    @Test fun ansBeforeAnyResultIsAnError() {
        val (_, out) = CalcBook().submit("ans")
        assertEquals("No answer yet: ans is the last result", (out as CalcOutcome.Err).message)
    }

    @Test fun anAssignmentKeepsTheVariable() {
        val b = CalcBook().then("x = 3 * 4").then("x / 2")
        assertEquals("6", lastText(b))
        assertEquals(listOf("x"), b.named.map { it.first })
    }

    @Test fun aVariableKeepsItsUnit() = assertEquals("2000 m", lastText(CalcBook().then("d = 2 km").then("d in m")))

    @Test fun aFailedLineChangesNothing() {
        val b = CalcBook().then("1 + 1")
        val (after, out) = b.submit("1 +")
        assertTrue(out is CalcOutcome.Err)
        assertEquals(b, after)
    }

    @Test fun historyIsCapped() {
        var b = CalcBook()
        repeat(CalcBook.MAX_HISTORY + 5) { b = b.then("$it + 1") }
        assertEquals(CalcBook.MAX_HISTORY, b.history.size)
        assertEquals("${CalcBook.MAX_HISTORY + 4} + 1", b.history.first().input)
    }

    @Test fun aPreviewIsNotKept() {
        val b = CalcBook()
        assertEquals("4", (b.preview("2 + 2") as CalcOutcome.Ok).result.text)
        assertTrue(b.history.isEmpty())
    }

    @Test fun clearingTheHistoryKeepsTheVariables() {
        val b = CalcBook().then("x = 5").clearHistory()
        assertTrue(b.history.isEmpty())
        assertEquals(listOf("x"), b.named.map { it.first })
    }

    @Test fun theShownNumberIsInTheAskedUnit() = assertEquals(2000.0, CalcBook().then("2 km in m").history.first().number, 1e-9)

    @Test fun aResultInsideTheRangeIsKept() = assertEquals(Fitted(1.5f, false), CalcSend.fit(1.5, -3f..3f, false))

    @Test fun aResultOutsideTheRangeIsClamped() = assertEquals(Fitted(5f, true), CalcSend.fit(10.0, 0f..5f, false))

    @Test fun aWholeNumberKnobGetsARoundedResult() = assertEquals(Fitted(3f, false), CalcSend.fit(2.6, 1f..8f, true))

    @Test fun roundingCanStillNeedClamping() = assertEquals(Fitted(8f, true), CalcSend.fit(8.6, 1f..8f, true))

    @Test fun theResultJackFeedsANumberInput() =
        assertEquals(listOf(Cable(CALC_RESULT, "glitch.seed")), RoutingDoc().connect(CALC_RESULT, "glitch.seed").cables)

    @Test fun theResultJackDoesNotFeedAPictureInput() = assertEquals(RoutingDoc(), RoutingDoc().connect(CALC_RESULT, "develop.in"))
}
