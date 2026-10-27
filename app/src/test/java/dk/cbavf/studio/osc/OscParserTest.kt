package dk.cbavf.studio.osc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OscParserTest {
    private fun str(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.US_ASCII)
        val padded = ((raw.size + 4) / 4) * 4   // always at least one zero byte
        return raw.copyOf(padded)
    }

    private fun f(v: Float) = ByteBuffer.allocate(4).putFloat(v).array()
    private fun i(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun packet(address: String, tags: String, vararg args: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(str(address)); out.write(str(tags)); args.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test fun aFloatMessage() {
        val m = OscParser.parse(packet("/studio/a", ",f", f(0.25f)))
        assertEquals(OscMessage("/studio/a", listOf(0.25f)), m)
    }

    @Test fun severalArgumentsInOrder() {
        val m = OscParser.parse(packet("/x", ",fif", f(1.5f), i(-3), f(7f)))
        assertEquals(OscMessage("/x", listOf(1.5f, -3f, 7f)), m)
    }

    @Test fun trueAndFalseHaveNoBytes() {
        assertEquals(OscMessage("/t", listOf(1f)), OscParser.parse(packet("/t", ",T")))
        assertEquals(OscMessage("/t", listOf(0f, 2f)), OscParser.parse(packet("/t", ",Fi", i(2))))
    }

    @Test fun anAddressWhoseLengthIsAMultipleOfFourStillHasItsPadding() {
        // "/abc" is 4 characters: it needs a whole extra word of zeros
        assertEquals(OscMessage("/abc", listOf(1f)), OscParser.parse(packet("/abc", ",f", f(1f))))
    }

    @Test fun badPacketsGiveNull() {
        assertNull(OscParser.parse(ByteArray(0)))
        assertNull(OscParser.parse("hello".toByteArray()))
        assertNull(OscParser.parse(str("#bundle") + ByteArray(8)))
        // cut short: the float is missing
        assertNull(OscParser.parse(packet("/x", ",f")))
        // no type tag string at all
        assertNull(OscParser.parse(str("/x")))
        // a type this reader does not know
        assertNull(OscParser.parse(packet("/x", ",s", str("hi"))))
    }

    @Test fun anAddressWithoutATagStringIsNotAMessage() {
        assertNull(OscParser.parse(str("/only/an/address")))
    }
}
