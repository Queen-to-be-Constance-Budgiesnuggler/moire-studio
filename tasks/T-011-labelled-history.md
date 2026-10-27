---
id: T-011
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/ui/history/History.kt]
test_src: tasks/tests/T-011/HistoryTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/ui/history/HistoryTest.kt
test_filter: dk.cbavf.studio.ui.history.HistoryTest
max_retries: 2
status: done                # model attempts failed (two declarations with one name); written by hand against the same tests, plus replaceCurrent
---
## Spec
A generic undo history with labels, for the listable undo panel.

```kotlin
class History<T>(initial: T, private val limit: Int = 100,
                 private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Entry<T>(val label: String, val timeMs: Long, val state: T)
    val entries: List<Entry<T>>
    val index: Int
    val current: T
    val canUndo: Boolean
    val canRedo: Boolean
    fun push(label: String, state: T)
    fun undo(): T?
    fun redo(): T?
    fun jumpTo(i: Int): T?
    fun labels(): List<String>
}
```

- Entry 0 is the initial state with the label `"Start"` and the time from `clock()`.
- `index` is the position of the current entry. `current` is `entries[index].state`.
- `push` first drops every entry after `index` (a new action ends the redo branch), then appends `Entry(label, clock(), state)` and sets `index` to the last entry. If there are now more than `limit` entries, drop entries from the front until there are exactly `limit`, and keep `index` on the last entry.
- `undo`: if `index > 0`, move back one and return the new `current`, otherwise return `null`. `redo`: if `index < entries.size - 1`, move forward one and return the new `current`, otherwise `null`. `jumpTo(i)`: if `i` is a valid entry position, set `index = i` and return the state, otherwise `null` and no change.
- `canUndo` is `index > 0`. `canRedo` is `index < entries.size - 1`. `labels()` returns the labels of all entries, oldest first. `entries` returns a read-only copy.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.ui.history`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`HistoryTest` passes.
