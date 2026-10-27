---
id: T-009
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/abcompare/AbGeometry.kt]
test_src: tasks/tests/T-009/AbGeometryTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/abcompare/AbGeometryTest.kt
test_filter: dk.cbavf.studio.abcompare.AbGeometryTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Pure geometry for the A/B compare hold gestures.

```kotlin
enum class AbMode { Input, SplitVertical, SplitHorizontal, Full }
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}
object AbGeometry {
    fun quadrantMode(x: Double, y: Double, w: Double, h: Double): AbMode
    fun splitBoxes(mode: AbMode, w: Double, h: Double, fraction: Double): Pair<Box, Box>
    fun fitCenter(srcW: Double, srcH: Double, dstW: Double, dstH: Double): Box
    fun holdStarts(durationMs: Long, moveDp: Double): Boolean
}
```

- `quadrantMode`: the canvas is `w` by `h`. Top-left (`x < w/2` and `y < h/2`) is `Input`. Top-right (`x >= w/2`, `y < h/2`) is `SplitVertical`. Bottom-left (`x < w/2`, `y >= h/2`) is `SplitHorizontal`. Bottom-right is `Full`.
- `splitBoxes`: returns the boxes for A and B. `fraction` is clamped to `0.02..0.98`. For `SplitVertical`: A is the left part `Box(0, 0, w*f, h)` and B is `Box(w*f, 0, w, h)`. For `SplitHorizontal`: A is the top part `Box(0, 0, w, h*f)` and B is `Box(0, h*f, w, h)`. For `Input` and `Full`, both are the whole canvas `Box(0, 0, w, h)`.
- `fitCenter`: the largest box with the aspect ratio of `srcW` by `srcH` that fits inside `dstW` by `dstH`, centred. If any of the four inputs is `<= 0`, return `Box(0.0, 0.0, 0.0, 0.0)`.
- `holdStarts`: true if `durationMs >= 500` and `moveDp < 8.0`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.abcompare`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a one-line KDoc on each public function (no @param or @return tags, keep it short).

## Acceptance
`AbGeometryTest` passes.
