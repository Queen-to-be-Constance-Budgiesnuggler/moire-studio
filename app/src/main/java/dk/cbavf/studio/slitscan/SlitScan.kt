package dk.cbavf.studio.slitscan

import kotlin.math.ceil

object SlitScan {
    /**
     * Calculates the number of slits across the scan axis.
     */
    fun slitCount(width: Int, height: Int, slitPx: Int, vertical: Boolean): Int {
        if (slitPx < 1) throw IllegalArgumentException()
        return if (vertical) {
            ceil(width / slitPx.toDouble()).toInt()
        } else {
            ceil(height / slitPx.toDouble()).toInt()
        }
    }

    /**
     * Performs slit-scan sampling on a list of frames.
     */
    fun scan(frames: List<IntArray>, width: Int, height: Int, slitPx: Int,
             vertical: Boolean, offsets: IntArray? = null): IntArray {
        if (frames.isEmpty()) throw IllegalArgumentException()
        if (slitPx < 1) throw IllegalArgumentException()
        if (width <= 0 || height <= 0) throw IllegalArgumentException()
        for (frame in frames) {
            if (frame.size != width * height) throw IllegalArgumentException()
        }

        val output = IntArray(width * height)
        val slitCount = slitCount(width, height, slitPx, vertical)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val s = if (vertical) {
                    x / slitPx
                } else {
                    y / slitPx
                }
                
                val offset = if (offsets != null && s < offsets.size) {
                    offsets[s]
                } else {
                    0
                }
                
                val frameIndex = Math.floorMod(s + offset, frames.size)
                val pixelIndex = y * width + x
                output[pixelIndex] = frames[frameIndex][pixelIndex]
            }
        }
        
        return output
    }
}
