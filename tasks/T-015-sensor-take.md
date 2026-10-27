---
id: T-015
executor: local            # local | claude | user
wave: 5
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/sensors/SensorTake.kt]
test_src: tasks/tests/T-015/SensorTakeTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/sensors/SensorTakeTest.kt
test_filter: dk.cbavf.studio.sensors.SensorTakeTest
max_retries: 2
status: failed 
---
## Spec
Record and replay of a sensor stream, as plain Kotlin in package `dk.cbavf.studio.sensors`. Use `kotlinx.serialization` (`@Serializable`) for the data class. No Android imports.

```kotlin
@Serializable
data class SensorTake(val hz: Int, val kinds: List<String>, val frames: List<List<Float>>) {
    val durationMs: Long
    fun valueAt(tMs: Long, loop: Boolean = false): List<Float>
}

class TakeRecorder(private val kinds: List<String>, private val hz: Int) {
    fun add(tMs: Long, values: List<Float>)
    fun finish(): SensorTake
}
```

- A take holds `frames`: one list of values per sample, each as long as `kinds`, sampled `hz` times a second, starting at time 0. `durationMs` is `(frames.size - 1) * 1000 / hz` (0 when there are fewer than two frames).
- `TakeRecorder.add(tMs, values)` is called with the time in milliseconds since the recording started, as often as the caller likes (much more often than `hz`). It keeps a frame only when `tMs` is at or past the time the next frame is due: the first call always keeps a frame (and that frame is frame 0, whatever `tMs` was), then the next one is due at `frames.size * 1000 / hz` milliseconds. Each kept frame is a copy of `values`. If `values.size != kinds.size` the call is ignored. `finish()` returns the take with `hz`, `kinds` and the kept frames.
- `valueAt(tMs, loop)` returns the values at time `tMs`, linearly interpolated between the two frames around it: with `pos = tMs * hz / 1000.0` (a Double), frame `i = floor(pos)` and fraction `f = pos - i`, the result for each kind is `frames[i][k] * (1 - f) + frames[i+1][k] * f`. With `loop = false`, a time before 0 gives the first frame and a time at or past the end gives the last frame. With `loop = true`, `tMs` is first wrapped into `0 until durationMs` (use `Math.floorMod`; when `durationMs` is 0, treat as not looping). An empty take returns an empty list; a take with one frame always returns that frame.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.sensors`, import exactly what you use (`kotlinx.serialization.Serializable`, and `kotlin.math.floor` if you use it), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a KDoc on each public class and function that states the rule in plain words.

## Acceptance
`SensorTakeTest` passes.
