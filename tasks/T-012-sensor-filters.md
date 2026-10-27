---
id: T-012
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/sensors/SensorFilters.kt]
test_src: tasks/tests/T-012/SensorFiltersTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/sensors/SensorFiltersTest.kt
test_filter: dk.cbavf.studio.sensors.SensorFiltersTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Small signal filters for sensor streams, plus a seed hash. Declare all of these in one file:

```kotlin
class LowPass(alpha: Double) { fun next(x: Double): Double; fun reset() }
class HighPass(alpha: Double) { fun next(x: Double): Double; fun reset() }
class Derivative { fun next(x: Double, dtSec: Double): Double; fun reset() }
class Hysteresis(val high: Double, val low: Double) {
    fun next(x: Double): Boolean        // current on/off state after this sample
    fun rising(x: Double): Boolean      // true only on the sample where the state turns on
    fun reset()
}
object SeedHash { fun splitMix(x: Long): Long; fun fromFloats(values: FloatArray): Long }
```

- `LowPass`: `alpha` is clamped to `0.0..1.0`. The first sample returns itself. After that `y = y + alpha * (x - y)`. `reset()` forgets the state.
- `HighPass`: owns a `LowPass(alpha)` and returns `x - lowpass.next(x)` (so the first sample returns 0.0).
- `Derivative`: the first call (or the first after `reset`) returns `0.0` and stores `x`. After that it returns `(x - previous) / dtSec`, or `0.0` if `dtSec <= 0.0`, and stores `x`.
- `Hysteresis`: starts off. `next`: if `x >= high` the state becomes on, if `x <= low` it becomes off, otherwise it stays; returns the state. `rising` calls `next` and returns true only if the state changed from off to on on this call.
- `SeedHash.splitMix(x)`: the SplitMix64 mixer: `z = x + 0x9E3779B97F4A7C15`; `z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9`; `z = (z xor (z ushr 27)) * 0x94D049BB133111EB`; return `z xor (z ushr 31)`. Write the hex constants as `0x9E3779B97F4A7C15uL.toLong()` (unsigned literal converted to Long) so they compile.
- `fromFloats`: an empty array returns `0L`. Otherwise start with `s = 0L` and for each value `s = splitMix(s xor v.toRawBits().toLong())`, and return `s`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.sensors`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`SensorFiltersTest` passes.
