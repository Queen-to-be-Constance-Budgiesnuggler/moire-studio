package dk.cbavf.studio.slitscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceSlitsTest {
    @Test fun anEmptyTimelineGivesNoFrames() {
        assertEquals(emptyList<Int>(), SequenceSlits.frameOrder(emptyList(), byTime = false))
        assertEquals(emptyList<Int>(), SequenceSlits.frameOrder(emptyList(), byTime = true))
    }

    @Test fun onePerItemIgnoresTheTiming() {
        assertEquals(listOf(0, 1, 2), SequenceSlits.frameOrder(listOf(5, 1, 30), byTime = false))
    }

    @Test fun byTimeGivesALongerItemMoreFrames() {
        assertEquals(listOf(0, 1, 1, 1), SequenceSlits.frameOrder(listOf(1, 3), byTime = true))
    }

    @Test fun byTimeSamplesALongTimelineAtEvenSteps() {
        val order = SequenceSlits.frameOrder(listOf(1000, 1000), byTime = true, maxFrames = 10)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1), order)
    }

    @Test fun byTimeNeverGivesMoreThanTheCap() {
        val order = SequenceSlits.frameOrder(List(20) { 60 }, byTime = true)
        assertEquals(SequenceSlits.MAX_FRAMES, order.size)
        assertEquals(0, order.first())
        assertEquals(19, order.last())
    }

    @Test fun ticksUnderOneCountAsOne() {
        assertEquals(listOf(0, 1, 2), SequenceSlits.frameOrder(listOf(0, -3, 1), byTime = true))
    }

    @Test fun onlyTheFirstItemsAreUsed() {
        val order = SequenceSlits.frameOrder(List(100) { 1 }, byTime = false)
        assertEquals(SequenceSlits.MAX_ITEMS, order.size)
        assertTrue(order.all { it < SequenceSlits.MAX_ITEMS })
    }

    @Test fun frameOrderRisesAndEachItemIsDecodedOnce() {
        val order = SequenceSlits.frameOrder(listOf(2, 1, 4), byTime = true)
        assertEquals(order.sorted(), order)
        assertEquals(listOf(0, 1, 2), SequenceSlits.itemsUsed(order))
    }

    @Test fun aShortItemCanBeSkippedWhenTheTimelineIsSampled() {
        // One tick in a thousand falls between two steps.
        val order = SequenceSlits.frameOrder(listOf(500, 1, 499), byTime = true, maxFrames = 3)
        assertEquals(listOf(0, 2), SequenceSlits.itemsUsed(order))
    }
}
