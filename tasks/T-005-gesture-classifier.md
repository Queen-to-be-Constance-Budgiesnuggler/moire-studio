---
id: T-005
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/ui/gestures/GestureClassifier.kt]
test_src: tasks/tests/T-005/GestureClassifierTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/ui/gestures/GestureClassifierTest.kt
test_filter: dk.cbavf.studio.ui.gestures.GestureClassifierTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Create `enum class Gesture { None, Tap1, Tap2, Tap3, Tap4, LongPress1, LongPress2, Swipe3Left, Swipe3Right, EdgeSwipeRight }`, `data class TouchSummary` and `object GestureClassifier`.

```kotlin
data class TouchSummary(
    val fingers: Int, val durationMs: Long, val maxMoveDp: Double,
    val dx: Double, val dy: Double,          // net movement in dp, from first touch to release (centroid)
    val startX: Double, val width: Double)   // start x and total width in dp
object GestureClassifier { fun classify(s: TouchSummary): Gesture }
```

Rules, checked in this order, using these constants (declare them as `const val` in the object): `TAP_MAX_MS = 250L`, `LONG_MIN_MS = 500L`, `STILL_DP = 8.0`, `SWIPE_MIN_DP = 80.0`, `SWIPE_MAX_MS = 600L`, `EDGE_DP = 24.0`, `EDGE_SWIPE_MIN_DP = 60.0`.
1. Three-finger swipe: `fingers == 3`, `durationMs <= SWIPE_MAX_MS`, `abs(dx) >= SWIPE_MIN_DP` and `abs(dx) > 2 * abs(dy)`. `dx < 0` gives `Swipe3Left`, otherwise `Swipe3Right`.
2. Edge swipe: `fingers == 1`, `startX >= width - EDGE_DP`, `dx <= -EDGE_SWIPE_MIN_DP`, and `abs(dx) > 2 * abs(dy)` gives `EdgeSwipeRight`.
3. Tap: `durationMs <= TAP_MAX_MS` and `maxMoveDp <= STILL_DP`: 1, 2, 3 or 4 fingers give `Tap1` to `Tap4`.
4. Long press: `durationMs >= LONG_MIN_MS` and `maxMoveDp <= STILL_DP`: 1 finger gives `LongPress1`, 2 fingers `LongPress2`.
5. Anything else is `None`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.ui.gestures`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`GestureClassifierTest` passes.
