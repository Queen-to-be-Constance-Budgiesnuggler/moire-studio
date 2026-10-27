---
id: T-010
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/slitscan/SlitScan.kt]
test_src: tasks/tests/T-010/SlitScanTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/slitscan/SlitScanTest.kt
test_filter: dk.cbavf.studio.slitscan.SlitScanTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Slit-scan sampling over pixel arrays (ARGB ints, row major, `index = y * width + x`).

```kotlin
object SlitScan {
    fun slitCount(width: Int, height: Int, slitPx: Int, vertical: Boolean): Int
    fun scan(frames: List<IntArray>, width: Int, height: Int, slitPx: Int,
             vertical: Boolean, offsets: IntArray? = null): IntArray
}
```

- `slitCount`: the number of slits across the scan axis: `ceil(width / slitPx)` for vertical slits (each slit is a column strip), `ceil(height / slitPx)` for horizontal (a row strip). Throw `IllegalArgumentException` if `slitPx < 1`.
- `scan`: output has `width * height` pixels. For a pixel at `(x, y)` the slit index is `s = x / slitPx` (integer division) if `vertical`, otherwise `s = y / slitPx`. The offset is `offsets[s]` if `offsets` is not null and `s` is inside it, otherwise 0. The frame used is `Math.floorMod(s + offset, frames.size)`. The output pixel is that frame's pixel at the same `(x, y)`.
- Throw `IllegalArgumentException` if `frames` is empty, if `slitPx < 1`, if `width` or `height` is `<= 0`, or if any frame's size is not `width * height`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.slitscan`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`SlitScanTest` passes.
