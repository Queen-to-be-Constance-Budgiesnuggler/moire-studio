package dk.cbavf.studio.ui.gestures

import kotlin.math.abs

enum class Gesture {
    None, Tap1, Tap2, Tap3, Tap4, LongPress1, LongPress2, Swipe3Left, Swipe3Right, EdgeSwipeRight
}

data class TouchSummary(
    val fingers: Int, val durationMs: Long, val maxMoveDp: Double,
    val dx: Double, val dy: Double,          // net movement in dp, from first touch to release (centroid)
    val startX: Double, val width: Double)   // start x and total width in dp

object GestureClassifier {
    const val TAP_MAX_MS = 250L
    const val LONG_MIN_MS = 500L
    const val STILL_DP = 8.0
    const val SWIPE_MIN_DP = 80.0
    const val SWIPE_MAX_MS = 600L
    const val EDGE_DP = 24.0
    const val EDGE_SWIPE_MIN_DP = 60.0

    /**
     * Classifies a touch gesture based on the provided summary.
     *
     * The classification follows these rules in order:
     * 1. Three-finger swipe: fingers == 3, durationMs <= SWIPE_MAX_MS,
     *    abs(dx) >= SWIPE_MIN_DP and abs(dx) > 2 * abs(dy). dx < 0 gives Swipe3Left,
     *    otherwise Swipe3Right.
     * 2. Edge swipe: fingers == 1, startX >= width - EDGE_DP, dx <= -EDGE_SWIPE_MIN_DP,
     *    and abs(dx) > 2 * abs(dy) gives EdgeSwipeRight.
     * 3. Tap: durationMs <= TAP_MAX_MS and maxMoveDp <= STILL_DP:
     *    1, 2, 3 or 4 fingers give Tap1 to Tap4.
     * 4. Long press: durationMs >= LONG_MIN_MS and maxMoveDp <= STILL_DP:
     *    1 finger gives LongPress1, 2 fingers LongPress2.
     * 5. Anything else is None.
     */
    fun classify(s: TouchSummary): Gesture {
        // Three-finger swipe
        if (s.fingers == 3 && s.durationMs <= SWIPE_MAX_MS && 
            abs(s.dx) >= SWIPE_MIN_DP && abs(s.dx) > 2 * abs(s.dy)) {
            return if (s.dx < 0) Gesture.Swipe3Left else Gesture.Swipe3Right
        }

        // Edge swipe
        if (s.fingers == 1 && s.startX >= s.width - EDGE_DP && 
            s.dx <= -EDGE_SWIPE_MIN_DP && abs(s.dx) > 2 * abs(s.dy)) {
            return Gesture.EdgeSwipeRight
        }

        // Tap
        if (s.durationMs <= TAP_MAX_MS && s.maxMoveDp <= STILL_DP) {
            return when (s.fingers) {
                1 -> Gesture.Tap1
                2 -> Gesture.Tap2
                3 -> Gesture.Tap3
                4 -> Gesture.Tap4
                else -> Gesture.None
            }
        }

        // Long press
        if (s.durationMs >= LONG_MIN_MS && s.maxMoveDp <= STILL_DP) {
            return when (s.fingers) {
                1 -> Gesture.LongPress1
                2 -> Gesture.LongPress2
                else -> Gesture.None
            }
        }

        return Gesture.None
    }
}
