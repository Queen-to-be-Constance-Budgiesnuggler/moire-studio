package dk.cbavf.studio.channels

/**
 * Splitting a picture into one grey plane per colour channel, and putting planes back together, for the Node Lab's Split Channel
 * and Merge Channels nodes. A plane is an array of opaque grey pixels whose level is the channel's value scaled to 0..1
 * (see [ChannelValue.normalised]: Lab's L is L/100 and a, b run from -128..127 to 0..1).
 */
object ChannelPlanes {
    private fun grey(v: Float): Int {
        val level = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }

    /** The plane of channel [channel] of [space] for every pixel of [pixels]. */
    fun split(space: ColorSpace, channel: Int, pixels: IntArray): IntArray =
        IntArray(pixels.size) { grey(ChannelValue.normalised(space, channel, pixels[it])) }

    /** The level a channel takes when no plane is given for it: opaque alpha, neutral Lab a and b, otherwise zero. */
    fun neutral(space: ColorSpace, channel: Int): Float = when {
        space == ColorSpace.RGBA && channel == 3 -> 1f
        space == ColorSpace.LAB && channel > 0 -> (128f / 255f)
        else -> 0f
    }

    /**
     * Puts planes back into one picture in [space]. [planes] has one entry per channel of the space (null = none given, so the
     * channel's [neutral] level is used); each given plane's level is its grey (the blue byte). All planes must be as long as
     * [size].
     */
    fun merge(space: ColorSpace, planes: List<IntArray?>, size: Int): IntArray {
        val n = space.channelNames.size
        val values = DoubleArray(n)
        return IntArray(size) { i ->
            for (c in 0 until n) {
                val v = planes.getOrNull(c)?.get(i)?.let { (it and 0xFF) / 255f } ?: neutral(space, c)
                values[c] = when {
                    space == ColorSpace.LAB && c == 0 -> v * 100.0
                    space == ColorSpace.LAB -> v * 255.0 - 128.0
                    else -> v.toDouble()
                }
            }
            val rgba = ColorConv.toRgba(space, values)
            fun byte(x: Double) = (x.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
            (byte(rgba[3]) shl 24) or (byte(rgba[0]) shl 16) or (byte(rgba[1]) shl 8) or byte(rgba[2])
        }
    }
}
