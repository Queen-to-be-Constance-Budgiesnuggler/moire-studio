package dk.cbavf.studio.channels

/** Reads one channel of an ARGB pixel in any [ColorSpace], as a 0..1 number (for height maps, planes, previews). */
object ChannelValue {
    /** How many channels of [space] an opaque image has: RGBA shows R, G, B, and A only when asked for it. */
    fun colorChannels(space: ColorSpace): Int = if (space == ColorSpace.RGBA) 3 else space.channelNames.size

    /**
     * Channel [channel] of the colour [argb] in [space], scaled to 0..1: RGB, alpha and CMYK as they are,
     * Lab's L as L/100 and a, b from -128..127 to 0..1.
     */
    fun normalised(space: ColorSpace, channel: Int, argb: Int): Float {
        val a = (argb ushr 24 and 0xFF) / 255.0
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val v = ColorConv.fromRgba(space, r, g, b, a)
        val x = v.getOrElse(channel) { 0.0 }
        val out = if (space == ColorSpace.LAB) {
            if (channel == 0) x / 100.0 else (x + 128.0) / 255.0
        } else x
        return out.coerceIn(0.0, 1.0).toFloat()
    }
}
