---
id: T-008
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/debug/DebugLog.kt]
test_src: tasks/tests/T-008/DebugLogTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/debug/DebugLogTest.kt
test_filter: dk.cbavf.studio.debug.DebugLogTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
A ring buffer logger for the in-app debug terminal. No Android classes.

```kotlin
class DebugLog(var level: Int = 0, private val capacity: Int = 2000,
               private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Entry(val timeMs: Long, val level: Int, val tag: String, val msg: String)
    val size: Int
    fun log(level: Int, tag: String, msg: String)
    fun entries(): List<Entry>
    fun lines(): List<String>
    fun clear()
    companion object { const val OFF = 0; const val ERROR = 1; const val INFO = 2; const val TRACE = 3 }
}
```

- `log` stores the entry only if `level >= 1` and `level <= this.level`. `this.level` can be changed at any time. A `level` of 0 on the logger logs nothing.
- When the buffer already holds `capacity` entries, the oldest entry is dropped. `entries()` returns oldest first.
- `lines()` formats each entry as `HH:MM:SS.mmm L/tag: msg`, from the entry's `timeMs` as time of day in UTC (hours = `timeMs / 3600000 % 24`, and so on), with zero padding (`%02d:%02d:%02d.%03d`). `L` is `E` for level 1, `I` for 2 and `T` for 3.
- All methods are `@Synchronized` (thread safe). `clear()` empties the buffer.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.debug`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`DebugLogTest` passes.
