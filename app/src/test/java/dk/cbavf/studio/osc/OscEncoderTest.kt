package dk.cbavf.studio.osc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OscEncoderTest {
    @Test fun aMessageReadsBackThroughTheParser() {
        val bytes = OscEncoder.encode("/studio/a", listOf(0.25f))
        assertEquals(OscMessage("/studio/a", listOf(0.25f)), OscParser.parse(bytes))
    }

    @Test fun severalFloatsKeepTheirOrder() {
        val args = listOf(0f, 1f, -0.5f, 3.75f)
        assertEquals(OscMessage("/studio/rgb", args), OscParser.parse(OscEncoder.encode("/studio/rgb", args)))
    }

    @Test fun noArgumentsIsValid() {
        assertEquals(OscMessage("/studio/ping", emptyList()), OscParser.parse(OscEncoder.encode("/studio/ping", emptyList())))
    }

    @Test fun everyPartIsPaddedToFourBytes() {
        // "/a" is 2 bytes, so it needs 2 NULs; ",f" is 2 bytes, so it needs 2 NULs; one float is 4 bytes
        assertArrayEquals(
            byteArrayOf('/'.code.toByte(), 'a'.code.toByte(), 0, 0, ','.code.toByte(), 'f'.code.toByte(), 0, 0, 0x3F, 0x80.toByte(), 0, 0),
            OscEncoder.encode("/a", listOf(1f)),
        )
    }

    @Test fun theLengthIsAlwaysAMultipleOfFour() {
        for (addr in listOf("/a", "/abc", "/abcd", "/studio/long/address")) for (n in 0..5) {
            assertEquals(0, OscEncoder.encode(addr, List(n) { 0.5f }).size % 4)
        }
    }

    @Test fun anAddressWithoutTheSlashIsRefused() {
        val refused = runCatching { OscEncoder.encode("studio/a", listOf(1f)) }.isFailure
        assertTrue(refused)
    }

    @Test fun parserStillRejectsEmptyInput() {
        assertNull(OscParser.parse(ByteArray(0)))
    }
}
