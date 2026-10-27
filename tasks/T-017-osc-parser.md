---
id: T-017
executor: local
wave: 5
depends: []
files_out: [app/src/main/java/dk/cbavf/studio/osc/OscParser.kt]
test_src: tasks/tests/T-017/OscParserTest.kt
test_dest: app/src/test/java/dk/cbavf/studio/osc/OscParserTest.kt
test_filter: dk.cbavf.studio.osc.OscParserTest
max_retries: 2
status: failed 
---
## Spec
A reader for OSC 1.0 messages (as sent over UDP), plain Kotlin in package `dk.cbavf.studio.osc`, no Android imports.

```kotlin
data class OscMessage(val address: String, val args: List<Float>)

object OscParser {
    fun parse(packet: ByteArray): OscMessage?
}
```

- An OSC message is: the address string, then the type tag string (it starts with a comma), then the arguments. A string is its ASCII bytes, then at least one zero byte, then more zero bytes so that the total length is a multiple of 4. Numbers are big-endian 4-byte values.
- `parse` returns null when the packet is empty, does not start with a slash, is a bundle (starts with `#bundle`), is cut short, or has no type tag string.
- Type tags it understands: `f` (a 32-bit float, read as big-endian bits to `Float`), `i` (a 32-bit signed integer, kept as a Float), `T` (true, 1.0, no bytes), `F` (false, 0.0, no bytes). A tag it does not understand (for example `s`) makes the whole parse return null.
- The result holds the address and the numeric arguments in order.
- Never throw: any problem returns null.

Rules for the file: pure Kotlin with no Android imports, package line `package dk.cbavf.studio.osc`, import exactly what you use (for example `java.nio.ByteBuffer`), plain statements with local `val`s and early `return`s, count your parentheses, KDoc on each public class and function.

## Acceptance
`OscParserTest` passes.
