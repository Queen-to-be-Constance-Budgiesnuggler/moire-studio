package dk.cbavf.studio.osc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Writes OSC 1.0 messages with float arguments: the counterpart of [OscParser], used for OSC out.
 * Strings are NUL-terminated and padded to a multiple of four bytes; floats are big-endian 32-bit.
 */
object OscEncoder {
    fun encode(address: String, args: List<Float>): ByteArray {
        require(address.startsWith("/")) { "an OSC address starts with /" }
        val out = ByteArrayOutputStream()
        out.write(padded(address))
        out.write(padded("," + "f".repeat(args.size)))
        for (a in args) out.write(ByteBuffer.allocate(4).putFloat(a).array())
        return out.toByteArray()
    }

    /** The bytes of [s] plus at least one NUL, padded with NULs to a multiple of four. */
    private fun padded(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.US_ASCII)
        val size = ((raw.size + 4) / 4) * 4
        return raw.copyOf(size)
    }
}
