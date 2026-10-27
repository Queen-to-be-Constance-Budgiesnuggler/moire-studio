---
id: T-002
executor: local            # local | claude | user
wave: 1
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/channels/ColorConv.kt]
test_src: tasks/tests/T-002/ColorConvTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/channels/ColorConvTest.kt
test_filter: dk.cbavf.studio.channels.ColorConvTest
max_retries: 2
status: done                # reviewed and copied into the main working copy
---
## Spec
Create `enum class ColorSpace(val channelNames: List<String>)` with entries `RGBA(listOf("R","G","B","A"))`, `CMYK(listOf("C","M","Y","K"))` and `LAB(listOf("L","a","b"))`, and `object ColorConv`. All channel values are `Double`. RGB inputs and outputs are sRGB in 0..1.

```kotlin
fun rgbToCmyk(r: Double, g: Double, b: Double): DoubleArray   // [c,m,y,k], each 0..1
fun cmykToRgb(c: Double, m: Double, y: Double, k: Double): DoubleArray  // [r,g,b], each 0..1
fun rgbToLab(r: Double, g: Double, b: Double): DoubleArray   // [L,a,b]; L in 0..100, D65
fun labToRgb(l: Double, a: Double, b: Double): DoubleArray   // [r,g,b], clamped to 0..1
fun fromRgba(space: ColorSpace, r: Double, g: Double, b: Double, a: Double): DoubleArray
fun toRgba(space: ColorSpace, values: DoubleArray): DoubleArray   // [r,g,b,a]
```

- CMYK (naive, no ICC profile): `k = 1 - max(r,g,b)`. If `k == 1.0` then `c = m = y = 0`. Otherwise `c = (1 - r - k) / (1 - k)`, and likewise for `m` (with g) and `y` (with b). Inverse: `r = (1 - c) * (1 - k)`, and likewise for g and b.
- Lab, sRGB to Lab with the D65 white point: first linearise each channel (`v <= 0.04045` gives `v / 12.92`, otherwise `((v + 0.055) / 1.055)^2.4`). Then XYZ = matrix times linear rgb with rows `[0.4124564, 0.3575761, 0.1804375]`, `[0.2126729, 0.7151522, 0.0721750]`, `[0.0193339, 0.1191920, 0.9503041]`. Divide by the white `Xn = 0.95047, Yn = 1.0, Zn = 1.08883`. `f(t) = cbrt(t)` when `t > 216.0/24389.0`, otherwise `(24389.0/27.0 * t + 16.0) / 116.0`. `L = 116 * f(y) - 16`, `a = 500 * (f(x) - f(y))`, `b = 200 * (f(y) - f(z))`. `labToRgb` is the exact inverse (use `finv(t) = t^3` when `t^3 > 216.0/24389.0`, otherwise `(116.0 * t - 16.0) / (24389.0/27.0)`), then the inverse matrix, then re-apply the sRGB gamma (`v <= 0.0031308` gives `12.92 * v`, otherwise `1.055 * v^(1/2.4) - 0.055`), then clamp each result to 0..1. The inverse matrix rows are `[3.2404542, -1.5371385, -0.4985314]`, `[-0.9692660, 1.8760108, 0.0415560]`, `[0.0556434, -0.2040259, 1.0572252]`. For `cbrt` use `Math.cbrt` (not a kotlin.math function).
- `fromRgba`: RGBA returns `[r,g,b,a]` unchanged. CMYK returns the 4 values from `rgbToCmyk`. LAB returns the 3 values from `rgbToLab`. `toRgba` is the inverse: RGBA returns the array as is, CMYK and LAB give `[r,g,b,1.0]`.

Rules for the file: pure Kotlin with no Android imports, start with the package line `package dk.cbavf.studio.channels`, import exactly what you use (every `kotlin.math` function you call must be imported), write function bodies as plain statements with local `val`s and early `return`s, count your parentheses, and put a KDoc on each public function that states the equation or rule in plain words.

## Acceptance
`ColorConvTest` passes.
