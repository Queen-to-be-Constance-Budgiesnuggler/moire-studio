---
id: T-016
executor: local
wave: 5
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/calibration/CalibrationSheet.kt]
test_src: tasks/tests/T-016/CalibrationSheetTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/calibration/CalibrationSheetTest.kt
test_filter: dk.cbavf.studio.calibration.CalibrationSheetTest
max_retries: 2
status: failed 
---
## Spec
A printable pitch test sheet as an SVG string, plain Kotlin in package `dk.cbavf.studio.calibration`, no Android imports.

```kotlin
object CalibrationSheet {
    fun svg(widthMm: Double, heightMm: Double, pitchesMm: List<Double>): String
    fun fmt(v: Double): String
}
```

- `fmt` writes a number with `String.format(java.util.Locale.US, "%.3f", v)` and then removes trailing zeros and a trailing dot (so 2.5 gives "2.5", 4.0 gives "4", 0.125 gives "0.125").
- `svg` returns one SVG document as text. The first line is `<svg xmlns="http://www.w3.org/2000/svg" width="WIDTHmm" height="HEIGHTmm" viewBox="0 0 WIDTH HEIGHT">` where WIDTH and HEIGHT are `fmt(widthMm)` and `fmt(heightMm)`, so one drawing unit is one millimetre. Then a white background `<rect x="0" y="0" width="WIDTH" height="HEIGHT" fill="#fff"/>`. Then the sheet is cut into `pitchesMm.size` bands stacked from the top, each `heightMm / pitchesMm.size` high (an empty list makes no bands). For the band `b` with pitch `p`: draw `floor(widthMm / p)` black bars, bar `i` (from 0) as `<rect x="X" y="Y" width="W" height="H" fill="#000"/>` with `X = i * p`, `Y = b * bandHeight`, `W = p / 2` and `H = bandHeight`; a pitch of zero or less draws no bars. Then a label for the band: `<text x="1" y="TY" font-size="3" fill="#f00">P mm</text>` where `TY = b * bandHeight + 4` and `P` is `fmt(p)`. Every number in the output goes through `fmt`. The last line is `</svg>`. Lines are separated by a newline character.

Rules for the file: pure Kotlin with no Android imports, package line `package dk.cbavf.studio.calibration`, import exactly what you use, plain statements with local `val`s, count your parentheses, KDoc on each public function stating the rule in plain words.

## Acceptance
`CalibrationSheetTest` passes.
