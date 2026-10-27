package dk.cbavf.studio.compose

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A dab (single brush stamp) for clone or heal: which pixels to read from [srcX],[srcY] and
 * write to [dstX],[dstY], with a soft circular [mask] of [size] x [size].
 */
data class CloneDab(
    val srcX: Int, val srcY: Int,
    val dstX: Int, val dstY: Int,
    val width: Int,
    val mask: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is CloneDab && srcX == other.srcX && srcY == other.srcY &&
                dstX == other.dstX && dstY == other.dstY && width == other.width && mask.contentEquals(other.mask)

    override fun hashCode(): Int = 31 * (31 * srcX + srcY) + dstX
}

/**
 * Pure helper for the clone and heal brushes. Works on [IntArray] pixel buffers (ARGB_8888
 * layout) so it can be unit-tested without Android graphics.
 */
object CloneBrush {

    /**
     * Plans a single dab: a soft circle of [radius] pixels, sampling from ([srcX],[srcY]) and
     * placing at ([dstX],[dstY]). The returned dab's patch is clamped so both source and
     * destination stay inside the [width] x [height] image. Returns `null` when the dab would
     * be entirely out of bounds or the radius is too small.
     */
    fun plan(
        srcX: Float, srcY: Float,
        dstX: Float, dstY: Float,
        radius: Float,
        width: Int, height: Int,
    ): CloneDab? {
        if (radius < 0.5f || width < 1 || height < 1) return null
        val r = radius.roundToInt().coerceAtLeast(1)
        val size = r * 2 + 1

        // Top-left of the full (unclamped) patch for source and destination.
        val sxFull = (srcX - r).roundToInt()
        val syFull = (srcY - r).roundToInt()
        val dxFull = (dstX - r).roundToInt()
        val dyFull = (dstY - r).roundToInt()

        // Clamp both patches to the image; the overlap must be positive.
        val x0 = maxOf(0, -sxFull, -dxFull)
        val y0 = maxOf(0, -syFull, -dyFull)
        val x1 = minOf(size, width - sxFull, width - dxFull)
        val y1 = minOf(size, height - syFull, height - dyFull)
        if (x1 <= x0 || y1 <= y0) return null

        val actualW = x1 - x0
        val actualH = y1 - y0

        // Build the soft circular mask for the clamped region.
        val mask = FloatArray(actualW * actualH)
        val cx = srcX - sxFull - x0
        val cy = srcY - syFull - y0
        for (py in 0 until actualH) {
            for (px in 0 until actualW) {
                val dx = px - cx
                val dy = py - cy
                val d = hypot(dx, dy) / radius
                mask[py * actualW + px] = (1f - d).coerceIn(0f, 1f)
            }
        }

        return CloneDab(
            srcX = sxFull + x0, srcY = syFull + y0,
            dstX = dxFull + x0, dstY = dyFull + y0,
            width = actualW, // the mask is actualW x actualH stored row-major
            mask = mask,
        )
    }

    /**
     * Applies a clone dab: copies source pixels to destination with the mask as alpha.
     * When [srcPixels] is provided, source samples come from the snapshot instead of [pixels],
     * so overlapping strokes do not read their own output.
     */
    fun applyClone(pixels: IntArray, width: Int, height: Int, dab: CloneDab, srcPixels: IntArray = pixels) {
        val maskW = dab.width
        val maskH = dab.mask.size / maskW
        for (py in 0 until maskH) {
            val srcRow = dab.srcY + py
            val dstRow = dab.dstY + py
            if (srcRow < 0 || srcRow >= height || dstRow < 0 || dstRow >= height) continue
            for (px in 0 until maskW) {
                val srcCol = dab.srcX + px
                val dstCol = dab.dstX + px
                if (srcCol < 0 || srcCol >= width || dstCol < 0 || dstCol >= width) continue
                val alpha = dab.mask[py * maskW + px]
                if (alpha <= 0f) continue
                val src = srcPixels[srcRow * width + srcCol]
                val dst = pixels[dstRow * width + dstCol]
                pixels[dstRow * width + dstCol] = blendPixel(src, dst, alpha)
            }
        }
    }

    /**
     * Applies a heal dab: copies the source's texture (high-frequency detail) but keeps the
     * destination's average colour. The simplified algorithm: compute the average colour in the
     * destination neighbourhood, then for each pixel blend (source + destination_avg - source_avg)
     * with the mask alpha.
     *
     * When [srcPixels] is provided, source samples come from the snapshot instead of [pixels],
     * so overlapping strokes do not read their own output.
     */
    fun applyHeal(pixels: IntArray, width: Int, height: Int, dab: CloneDab, srcPixels: IntArray = pixels) {
        val maskW = dab.width
        val maskH = dab.mask.size / maskW
        if (maskW < 1 || maskH < 1) return

        // Compute average colour for source and destination patches.
        val srcAvg = patchAverage(srcPixels, width, height, dab.srcX, dab.srcY, maskW, maskH, dab.mask)
        val dstAvg = patchAverage(srcPixels, width, height, dab.dstX, dab.dstY, maskW, maskH, dab.mask)

        // The colour shift: destination average minus source average.
        val shiftR = dstAvg[0] - srcAvg[0]
        val shiftG = dstAvg[1] - srcAvg[1]
        val shiftB = dstAvg[2] - srcAvg[2]

        for (py in 0 until maskH) {
            val srcRow = dab.srcY + py
            val dstRow = dab.dstY + py
            if (srcRow < 0 || srcRow >= height || dstRow < 0 || dstRow >= height) continue
            for (px in 0 until maskW) {
                val srcCol = dab.srcX + px
                val dstCol = dab.dstX + px
                if (srcCol < 0 || srcCol >= width || dstCol < 0 || dstCol >= width) continue
                val alpha = dab.mask[py * maskW + px]
                if (alpha <= 0f) continue
                val src = srcPixels[srcRow * width + srcCol]
                val dst = pixels[dstRow * width + dstCol]
                // Source texture shifted to match destination colour.
                val healedR = ((src shr 16 and 0xFF) + shiftR).roundToInt().coerceIn(0, 255)
                val healedG = ((src shr 8 and 0xFF) + shiftG).roundToInt().coerceIn(0, 255)
                val healedB = ((src and 0xFF) + shiftB).roundToInt().coerceIn(0, 255)
                val healedA = src ushr 24
                val healed = (healedA shl 24) or (healedR shl 16) or (healedG shl 8) or healedB
                pixels[dstRow * width + dstCol] = blendPixel(healed, dst, alpha)
            }
        }
    }

    /** Weighted average RGB of a patch, using the mask and pixel alpha as weights. */
    private fun patchAverage(
        pixels: IntArray, width: Int, height: Int,
        x0: Int, y0: Int, pw: Int, ph: Int, mask: FloatArray,
    ): FloatArray {
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var sumW = 0f
        for (py in 0 until ph) {
            val row = y0 + py
            if (row < 0 || row >= height) continue
            for (px in 0 until pw) {
                val col = x0 + px
                if (col < 0 || col >= width) continue
                val m = mask[py * pw + px]
                if (m <= 0f) continue
                val c = pixels[row * width + col]
                val a = (c ushr 24) / 255f
                val w = m * a
                if (w <= 0f) continue
                sumR += (c shr 16 and 0xFF) * w
                sumG += (c shr 8 and 0xFF) * w
                sumB += (c and 0xFF) * w
                sumW += w
            }
        }
        return if (sumW > 0f) floatArrayOf(sumR / sumW, sumG / sumW, sumB / sumW) else floatArrayOf(0f, 0f, 0f)
    }

    /** Blends [src] over [dst] at the given [alpha] (0..1), using premultiplied-alpha maths. */
    private fun blendPixel(src: Int, dst: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        val inv = 1f - a
        val srcA = (src ushr 24) / 255f
        val dstA = (dst ushr 24) / 255f
        val outA = srcA * a + dstA * inv
        if (outA < 1e-6f) return 0
        val srcPre = srcA * a
        val dstPre = dstA * inv
        val r = ((src shr 16 and 0xFF) * srcPre + (dst shr 16 and 0xFF) * dstPre) / outA
        val g = ((src shr 8 and 0xFF) * srcPre + (dst shr 8 and 0xFF) * dstPre) / outA
        val b = ((src and 0xFF) * srcPre + (dst and 0xFF) * dstPre) / outA
        return ((outA * 255).roundToInt().coerceIn(0, 255) shl 24) or
               (r.roundToInt().coerceIn(0, 255) shl 16) or
               (g.roundToInt().coerceIn(0, 255) shl 8) or
               b.roundToInt().coerceIn(0, 255)
    }
}
