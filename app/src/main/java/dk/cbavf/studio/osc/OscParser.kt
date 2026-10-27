package dk.cbavf.studio.osc

import java.nio.ByteBuffer

/**
 * Represents an OSC message with an address and a list of float arguments.
 */
data class OscMessage(val address: String, val args: List<Float>)

/**
 * Parses OSC 1.0 messages from byte arrays.
 */
object OscParser {
    /**
     * Parses an OSC message from a byte array.
     *
     * @param packet the raw OSC packet data
     * @return the parsed message or null if parsing fails
     */
    fun parse(packet: ByteArray): OscMessage? {
        if (packet.isEmpty()) return null
        
        val buffer = ByteBuffer.wrap(packet)
        
        // Check if it's a bundle (starts with #bundle)
        if (buffer.remaining() >= 6) {
            val peek = buffer.position()
            val bundlePrefix = ByteArray(6)
            buffer.get(bundlePrefix)
            buffer.position(peek)
            if (String(bundlePrefix, Charsets.US_ASCII) == "#bundle") {
                return null
            }
        }
        
        // Read address string
        val address = readString(buffer) ?: return null
        if (!address.startsWith("/")) return null
        
        // Read type tag string
        val typeTag = readString(buffer) ?: return null
        if (typeTag.isEmpty() || typeTag[0] != ',') return null
        
        // Parse arguments according to type tags
        val args = mutableListOf<Float>()
        for (i in 1 until typeTag.length) {
            when (typeTag[i]) {
                'f' -> {
                    if (buffer.remaining() < 4) return null
                    val floatBytes = ByteArray(4)
                    buffer.get(floatBytes)
                    val float = ByteBuffer.wrap(floatBytes).float
                    args.add(float)
                }
                'i' -> {
                    if (buffer.remaining() < 4) return null
                    val intBytes = ByteArray(4)
                    buffer.get(intBytes)
                    val int = ByteBuffer.wrap(intBytes).int
                    args.add(int.toFloat())
                }
                'T' -> args.add(1f)
                'F' -> args.add(0f)
                else -> return null // Unknown type tag
            }
        }
        
        return OscMessage(address, args)
    }
    
    /**
     * Reads one OSC string: its ASCII bytes, then at least one zero byte, then zeros up to the next multiple of four bytes. Leaves
     * the buffer after the padding; null if there is no terminating zero or the padding is cut short.
     */
    private fun readString(buffer: ByteBuffer): String? {
        val start = buffer.position()
        var end = start
        while (end < buffer.limit() && buffer.get(end) != 0.toByte()) end++
        if (end >= buffer.limit()) return null
        val padded = ((end - start + 4) / 4) * 4
        if (start + padded > buffer.limit()) return null
        val text = String(ByteArray(end - start) { buffer.get(start + it) }, Charsets.US_ASCII)
        buffer.position(start + padded)
        return text
    }
}
