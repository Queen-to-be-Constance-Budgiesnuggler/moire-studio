---
id: T-013
executor: local            # local | claude | user
wave: 4
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/math/Gate.kt]
test_src: tasks/tests/T-013/GateTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/math/GateTest.kt
test_filter: dk.cbavf.studio.math.GateTest
max_retries: 2
status: done                # 3 attempts failed one test (the delay trim); fixed by hand, copied into the main working copy
---
## Spec
Create the gate maths for Node Lab, as plain Kotlin with no Android imports, in package `dk.cbavf.studio.math`. A gate is a boolean that is held on for a while. A trigger is a boolean that is true for one update to mean an event.

```kotlin
enum class Edge { None, Rising, Falling }

class EdgeDetector {
    fun update(gate: Boolean): Edge
    fun reset()
}

object GateLogic {
    fun and(a: Boolean, b: Boolean): Boolean
    fun or(a: Boolean, b: Boolean): Boolean
    fun xor(a: Boolean, b: Boolean): Boolean
    fun not(a: Boolean): Boolean
}

class TriggerToGate(lengthSec: Double) {
    fun update(nowSec: Double, trigger: Boolean): Boolean
}

class GateDelay(delaySec: Double) {
    fun update(nowSec: Double, gate: Boolean): Boolean
}

class GateDivide(n: Int) {
    fun update(gate: Boolean): Boolean
}

class GateProbability(p: Double, seed: Long) {
    fun update(gate: Boolean): Boolean
}

class SampleAndHold {
    fun update(trigger: Boolean, input: Double): Double
}
```

- `EdgeDetector` remembers the previous gate, which starts as false. `update` returns `Rising` when the gate is true and the previous one was false, `Falling` when it is false and the previous one was true, otherwise `None`. `reset` makes the previous gate false again.
- `GateLogic` is the usual boolean logic: `xor` is true when exactly one input is true.
- `TriggerToGate`: when `trigger` is true, remember `nowSec` as the last trigger time. The result is true while a trigger has happened and `nowSec < lastTrigger + lengthSec` (strictly less). A new trigger restarts the length. Before any trigger the result is false. `lengthSec <= 0` never gives true.
- `GateDelay`: keep the history of `(nowSec, gate)` pairs passed to `update`. The result is the input gate as it was at time `nowSec - delaySec`: the gate of the latest history entry whose time is at most `nowSec - delaySec`, or false when there is no such entry. The current call's own pair is added to the history before the lookup. Drop history entries older than the one needed, so the list stays short (keep the latest entry at or before the lookup time, and everything after it).
- `GateDivide`: count the rising edges of the input (use an `EdgeDetector`). The output is true while the input gate is true and the number of rising edges so far is a multiple of `n` (`n < 1` is treated as 1); otherwise false.
- `GateProbability`: use `java.util.Random(seed)`. On each rising edge of the input draw `nextDouble()` once and remember `passing = draw < p`. The output is `gate && passing`. Two instances with the same `p` and seed give the same output for the same input.
- `SampleAndHold`: on a rising edge of `trigger` (it was false on the previous update, true now; the previous starts false) store `input`. Return the stored value, which starts at 0.0.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.math`, import exactly what you use, write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a KDoc on each public class and function that states the rule in plain words.

## Acceptance
`GateTest` passes.
