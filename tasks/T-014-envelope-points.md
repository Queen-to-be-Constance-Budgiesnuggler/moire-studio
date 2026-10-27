---
id: T-014
executor: local            # local | claude | user
wave: 4
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/math/EnvelopePoints.kt]
test_src: tasks/tests/T-014/EnvelopePointsTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/math/EnvelopePointsTest.kt
test_filter: dk.cbavf.studio.math.EnvelopePointsTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
The maths behind an envelope editor that draws an attack, decay, sustain, release shape and lets a finger drag its handles. Plain Kotlin with no Android imports, in package `dk.cbavf.studio.math`.

```kotlin
data class AdsrShape(val attackMs: Double, val decayMs: Double, val sustain: Double, val releaseMs: Double)

data class Handle(val x: Double, val y: Double)

object EnvelopePoints {
    const val HOLD = 0.15
    const val USABLE = 0.85

    fun handles(s: AdsrShape, axisMs: Double): List<Handle>
    fun drag(s: AdsrShape, index: Int, x: Double, y: Double, axisMs: Double): AdsrShape
}
```

The drawing area is 1 wide and 1 high, with x going right (time) and y going up (level). The time axis is `axisMs` milliseconds across the whole width. The sustain stretch is always drawn `HOLD` wide, whatever the sustain time. The four attack, decay and release durations plus the hold must fit, so the sum of `attackMs + decayMs + releaseMs` is at most `USABLE * axisMs`.

- `handles` returns four handles, in this order:
  0. the top of the attack: `x = attackMs / axisMs`, `y = 1.0`
  1. the end of the decay: `x = (attackMs + decayMs) / axisMs`, `y = sustain`
  2. the end of the sustain stretch: `x = handle 1's x + HOLD`, `y = sustain`
  3. the end of the release: `x = handle 2's x + releaseMs / axisMs`, `y = 0.0`
- `drag` returns a new shape after handle `index` was moved to `(x, y)`. All other handles follow from the new shape. Rules:
  - index 0: `attackMs = x * axisMs`, limited to between 0 and `USABLE * axisMs - decayMs - releaseMs` (and never below 0). `y` is ignored.
  - index 1: `decayMs = x * axisMs - attackMs`, limited to between 0 and `USABLE * axisMs - attackMs - releaseMs`. `sustain = y`, limited to 0..1.
  - index 2: only the level moves: `sustain = y`, limited to 0..1. `x` is ignored.
  - index 3: `releaseMs = (x - HOLD) * axisMs - attackMs - decayMs`, limited to between 0 and `USABLE * axisMs - attackMs - decayMs`.
  - any other index returns the shape unchanged.
  Limits are applied with `coerceIn`, using a lower bound of 0.0 even when the upper bound would be negative (then the result is 0.0).

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.math`, import exactly what you use, write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a KDoc on each public class and function that states the rule in plain words.

## Acceptance
`EnvelopePointsTest` passes.
