package dk.cbavf.studio.channels

/**
 * A setup for the colour channels of one picture: in a colour space, each channel gets a gain and an offset (and can be muted).
 * Anything that holds a picture can own one; the Node Lab's Channel Mix node is the first.
 */
data class ChannelSetup(
    val space: ColorSpace = ColorSpace.RGBA,
    /** One gain per channel of the space (1 = unchanged). */
    val gains: List<Float> = List(4) { 1f },
    /** One offset per channel, added after the gain, on the 0..1 scale of [ChannelPlanes] (0 = unchanged). */
    val offsets: List<Float> = List(4) { 0f },
    /** Channels set to their [ChannelPlanes.neutral] level whatever the picture holds. */
    val muted: Set<Int> = emptySet(),
) {
    /** True when applying this setup would change nothing. */
    val isNeutral: Boolean
        get() = muted.isEmpty() && gains.take(space.channelNames.size).all { it == 1f } && offsets.take(space.channelNames.size).all { it == 0f }

    /** [pixels] (ARGB) with every channel's gain, offset and mute applied; alpha is kept when the space has none of its own. */
    fun apply(pixels: IntArray): IntArray {
        if (isNeutral) return pixels
        val n = space.channelNames.size
        val planes = (0 until n).map { c ->
            val gain = gains.getOrElse(c) { 1f }
            val offset = offsets.getOrElse(c) { 0f }
            val neutral = ChannelPlanes.neutral(space, c)
            val src = ChannelPlanes.split(space, c, pixels)
            IntArray(pixels.size) { i ->
                val v = if (c in muted) neutral else ((src[i] and 0xFF) / 255f) * gain + offset
                val level = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            }
        }
        val out = ChannelPlanes.merge(space, planes, pixels.size)
        // CMYK and Lab have no alpha plane: keep the picture's own
        if (space != ColorSpace.RGBA) for (i in out.indices) out[i] = (out[i] and 0x00FFFFFF) or (pixels[i].toInt() and 0xFF000000.toInt())
        return out
    }
}
