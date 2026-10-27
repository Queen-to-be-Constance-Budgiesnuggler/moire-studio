package dk.cbavf.studio.ui.gestures

import org.junit.Assert.*
import org.junit.Test

class GestureClassifierTest {
    private fun s(f: Int, ms: Long, move: Double = 0.0, dx: Double = 0.0, dy: Double = 0.0, sx: Double = 100.0, w: Double = 400.0) =
        TouchSummary(f, ms, move, dx, dy, sx, w)

    @Test fun taps() {
        assertEquals(Gesture.Tap1, GestureClassifier.classify(s(1, 100)))
        assertEquals(Gesture.Tap2, GestureClassifier.classify(s(2, 250)))
        assertEquals(Gesture.Tap3, GestureClassifier.classify(s(3, 100, 8.0)))
        assertEquals(Gesture.Tap4, GestureClassifier.classify(s(4, 100)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(2, 251)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(2, 100, 8.5)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(5, 100)))
    }
    @Test fun longPresses() {
        assertEquals(Gesture.LongPress1, GestureClassifier.classify(s(1, 500)))
        assertEquals(Gesture.LongPress2, GestureClassifier.classify(s(2, 900, 3.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(2, 499)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(2, 900, 20.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(3, 900)))
    }
    @Test fun threeFingerSwipes() {
        assertEquals(Gesture.Swipe3Left, GestureClassifier.classify(s(3, 300, 90.0, dx = -90.0, dy = 10.0)))
        assertEquals(Gesture.Swipe3Right, GestureClassifier.classify(s(3, 600, 120.0, dx = 120.0, dy = 20.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(3, 601, 120.0, dx = 120.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(3, 300, 79.0, dx = 79.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(3, 300, 100.0, dx = 100.0, dy = 60.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(2, 300, 100.0, dx = 100.0)))
    }
    @Test fun edgeSwipe() {
        assertEquals(Gesture.EdgeSwipeRight, GestureClassifier.classify(s(1, 300, 80.0, dx = -80.0, dy = 5.0, sx = 390.0)))
        assertEquals(Gesture.EdgeSwipeRight, GestureClassifier.classify(s(1, 300, 60.0, dx = -60.0, sx = 376.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(1, 300, 80.0, dx = -80.0, sx = 300.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(1, 300, 40.0, dx = -40.0, sx = 390.0)))
        assertEquals(Gesture.None, GestureClassifier.classify(s(1, 300, 80.0, dx = 80.0, sx = 390.0)))
    }
}
