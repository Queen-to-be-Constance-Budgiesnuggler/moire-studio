package dk.cbavf.studio.math

/**
 * The Touch Pad node's maths. A finger on a pad of [width] x [height] is a point in 0..1 on each
 * side, with Y 0 at the top (as the Patch bay's Touch X/Y); a point maps to Min..Max.
 */
object TouchPad {
    /** Where a finger at ([px], [py]) is on the pad, each side 0..1 (held at the edges). */
    fun position(px: Float, py: Float, width: Float, height: Float): Pair<Float, Float> {
        val x = if (width > 0f) (px / width).coerceIn(0f, 1f) else 0.5f
        val y = if (height > 0f) (py / height).coerceIn(0f, 1f) else 0.5f
        return x to y
    }

    /** A pad side (0..1) as a number from [min] to [max]. */
    fun map(v: Float, min: Float, max: Float): Float = min + (max - min) * v.coerceIn(0f, 1f)
}
