---
id: T-003
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/math/Fringe.kt]
test_src: tasks/tests/T-003/FringeTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/math/FringeTest.kt
test_filter: dk.cbavf.studio.math.FringeTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Create `object Fringe` for the moire beat pattern of two line gratings. Grating 1 has pitch `p1` and its line normal along the x axis. Grating 2 has pitch `p2` and its normal rotated by `theta` (radians). Think in wave vectors: `k1 = (1/p1, 0)` and `k2 = (cos(theta)/p2, sin(theta)/p2)`. The beat wave vector is `kb = k1 - k2`.

```kotlin
fun beatVector(p1: Double, p2: Double, theta: Double): DoubleArray   // [kbx, kby]
fun beatPeriod(p1: Double, p2: Double, theta: Double): Double
fun fringeNormalAngle(p1: Double, p2: Double, theta: Double): Double
fun fringeLineAngle(p1: Double, p2: Double, theta: Double): Double
```

- `beatVector`: `[1/p1 - cos(theta)/p2, -sin(theta)/p2]`.
- `beatPeriod`: `1 / |kb|`. This equals `p1*p2 / sqrt(p1^2 + p2^2 - 2*p1*p2*cos(theta))`. If `|kb| < 1e-12`, return `Double.POSITIVE_INFINITY`.
- `fringeNormalAngle`: `atan2(kby, kbx)` in radians, the direction perpendicular to the fringe lines. Return `0.0` when `|kb| < 1e-12`.
- `fringeLineAngle`: the fringe normal angle plus `PI / 2`, wrapped into `(-PI/2, PI/2]` (add or subtract `PI` until it fits), because a line has no direction.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.math`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a KDoc on each public function that states the equation or rule in plain words.

## Acceptance
`FringeTest` passes.
