package dk.cbavf.studio.loop

import dk.cbavf.studio.sequence.model.AssetSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopFramesTest {
    private val seq = listOf(AssetSource("s0"), AssetSource("s1"), AssetSource("s2"))

    @Test
    fun theRacksPictureIsTheFirstFrame() {
        assertEquals(AssetSource("rack"), LoopFrames.first("rack", "pick", seq[0], "newest"))
    }

    @Test
    fun withoutACableTheLibraryPickThenTheSequenceThenTheNewestStill() {
        assertEquals(AssetSource("pick"), LoopFrames.first(null, "pick", seq[0], "newest"))
        assertEquals(seq[0], LoopFrames.first(null, null, seq[0], "newest"))
        assertEquals(AssetSource("newest"), LoopFrames.first(null, null, null, "newest"))
        assertNull(LoopFrames.first(null, null, null, null))
    }

    @Test
    fun feedbackShowsTheCaptureEvenWithACable() {
        assertTrue(LoopFrames.showsCapture(LoopSource.Feedback, "cap"))
        assertEquals(AssetSource("cap"), LoopFrames.next(LoopSource.Feedback, "cap", "rack", seq, 1, "s0"))
    }

    @Test
    fun feedbackWithNoCaptureFallsBackToTheRack() {
        assertFalse(LoopFrames.showsCapture(LoopSource.Feedback, null))
        assertEquals(AssetSource("rack"), LoopFrames.next(LoopSource.Feedback, null, "rack", seq, 1, "s0"))
    }

    @Test
    fun theRacksPictureTakesTheSequencesPlace() {
        assertFalse(LoopFrames.showsCapture(LoopSource.Sequence, "cap"))
        assertEquals(AssetSource("rack"), LoopFrames.next(LoopSource.Sequence, "cap", "rack", seq, 1, "s0"))
    }

    @Test
    fun withoutACableTheSequenceIsSteppedThroughAndWraps() {
        assertEquals(seq[1], LoopFrames.next(LoopSource.Sequence, "cap", null, seq, 1, "s0"))
        assertEquals(seq[0], LoopFrames.next(LoopSource.Sequence, "cap", null, seq, 3, "s2"))
    }

    @Test
    fun anEmptySequenceKeepsTheCaptureOrWhatWasShown() {
        assertEquals(AssetSource("cap"), LoopFrames.next(LoopSource.Sequence, "cap", null, emptyList(), 1, "s0"))
        assertEquals(AssetSource("s0"), LoopFrames.next(LoopSource.Sequence, null, null, emptyList(), 1, "s0"))
    }
}
