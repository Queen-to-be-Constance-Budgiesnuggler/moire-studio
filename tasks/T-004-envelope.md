---
id: T-004
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/math/Envelope.kt]
test_src: tasks/tests/T-004/EnvelopeTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/math/EnvelopeTest.kt
test_filter: dk.cbavf.studio.math.EnvelopeTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Create `enum class Curve { Linear, Exponential, Logarithmic, SCurve }` and `object Envelope`.

```kotlin
fun shape(x: Double, curve: Curve, amount: Double = 4.0): Double
fun adsr(tMs: Double, attackMs: Double, decayMs: Double, sustain: Double, releaseMs: Double,
         curve: Curve = Curve.Linear, amount: Double = 4.0, gateOffMs: Double? = null): Double
```

- `shape` maps `x` (clamped to 0..1) to 0..1, with `shape(0)=0` and `shape(1)=1` for every curve. `Linear` is `x`. `Exponential` is `(e^(amount*x) - 1) / (e^amount - 1)`; if `amount < 1e-6` it is linear. `Logarithmic` is `1 - shape(1 - x, Exponential, amount)`. `SCurve` is smoothstep `x*x*(3 - 2*x)`.
- `adsr` returns the envelope level (0..1) `tMs` milliseconds after the gate went on. `gateOffMs` is the time (ms after gate on) when the gate went off, or null while the gate is still held.
  - `tMs < 0` returns 0.
  - The **held level** at time `t` (as if the gate stays on): attack for `t < attackMs` is `shape(t / attackMs)`; then decay for `t < attackMs + decayMs` is `1 - (1 - sustain) * shape((t - attackMs) / decayMs)`; after that it is `sustain`. A zero-length attack or decay segment is skipped (the level jumps).
  - If `gateOffMs` is null or `tMs <= gateOffMs`, return the held level at `tMs`.
  - Otherwise release: `l0 = heldLevel(gateOffMs)`, `r = tMs - gateOffMs`. If `releaseMs <= 0` or `r >= releaseMs` return 0. Else return `l0 * (1 - shape(r / releaseMs))`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.math`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a KDoc on each public function that states the equation or rule in plain words.

## Acceptance
`EnvelopeTest` passes.
