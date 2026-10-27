---
id: T-001
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/math/Sliders.kt]
test_src: tasks/tests/T-001/SlidersTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/math/SlidersTest.kt
test_filter: dk.cbavf.studio.math.SlidersTest
max_retries: 2
status: done                # merged by copy into main working copy after review (agent/T-001 has the history)
---
## Spec
Create `object Sliders` in package `dk.cbavf.studio.math` (pure Kotlin, no Android imports). Start the file with exactly these imports, and use no others:

```kotlin
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
```

Write each function body as plain statements with local `val`s and early `return`s. Do not write long one-line `if/else` expressions with chained calls, and count your parentheses. It holds the value maths for the studio's two-zone slider knob. Every function is a pure function over `Double`. Put a KDoc on each function that states the equation in plain words.

```kotlin
fun fineStep(min: Double, max: Double, integer: Boolean = false): Double
fun snapToGrid(v: Double, min: Double, max: Double, step: Double): Double
fun nextStep(v: Double, min: Double, max: Double, step: Double, dir: Int): Double
fun fromFraction(t: Double, min: Double, max: Double): Double
fun toFraction(v: Double, min: Double, max: Double): Double
fun fromFractionLog(t: Double, min: Double, max: Double): Double
fun toFractionLog(v: Double, min: Double, max: Double): Double
```

- `fineStep`: `span = max - min`. If `span` is not finite or `span <= 0`, return `1.0`. Otherwise `10^(floor(log10(span)) - 2)`, which gives roughly 100 to 1000 steps across the range. If `integer` is true, never return less than `1.0`. Examples: span 1 gives 0.01, span 255 gives 1, span 360 gives 1, span 1000 gives 10, span 0.5 gives 0.001.
- `snapToGrid`: the grid starts at `min` and has spacing `step`. Compute `min + round((v - min) / step) * step`, using round-half-up (`floor(x + 0.5)`). Clamp the result to `min..max`, then round it to 10 decimal places (`round(x * 1e10) / 1e10`) to remove float drift such as `0.1 + 0.2`.
- `nextStep`: `snapToGrid(v + dir * step, min, max, step)`, with `dir` of +1 or -1.
- `fromFraction`: `min + t * (max - min)`, with `t` clamped to 0..1.
- `toFraction`: `(v - min) / (max - min)` clamped to 0..1, and `0.0` if `max == min`.
- `fromFractionLog`: `min * (max / min)^t`, with `t` clamped to 0..1. Throw `IllegalArgumentException` if `min <= 0` or `max <= 0`.
- `toFractionLog`: `ln(v / min) / ln(max / min)` clamped to 0..1, and `0.0` if `max == min`. Throw `IllegalArgumentException` if `min <= 0` or `max <= 0`. If `v <= 0` return `0.0`.

## Acceptance
The unit test `SlidersTest` (provided, hand-written by the orchestrator) passes. The file compiles with no warnings about unused code, and uses no imports other than `kotlin.math`.
