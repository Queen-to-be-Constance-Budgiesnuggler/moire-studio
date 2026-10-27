package dk.cbavf.studio.abcompare

import kotlin.math.min

enum class AbMode { Input, SplitVertical, SplitHorizontal, Full }

data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

object AbGeometry {
    /**
     * Determines the mode based on the quadrant of the given coordinates.
     */
    fun quadrantMode(x: Double, y: Double, w: Double, h: Double): AbMode {
        return when {
            x < w / 2 && y < h / 2 -> AbMode.Input
            x >= w / 2 && y < h / 2 -> AbMode.SplitVertical
            x < w / 2 && y >= h / 2 -> AbMode.SplitHorizontal
            else -> AbMode.Full
        }
    }

    /**
     * Splits the canvas into two boxes based on the mode and fraction.
     */
    fun splitBoxes(mode: AbMode, w: Double, h: Double, fraction: Double): Pair<Box, Box> {
        val f = fraction.coerceIn(0.02, 0.98)
        
        return when (mode) {
            AbMode.SplitVertical -> {
                val a = Box(0.0, 0.0, w * f, h)
                val b = Box(w * f, 0.0, w, h)
                Pair(a, b)
            }
            AbMode.SplitHorizontal -> {
                val a = Box(0.0, 0.0, w, h * f)
                val b = Box(0.0, h * f, w, h)
                Pair(a, b)
            }
            else -> {
                val box = Box(0.0, 0.0, w, h)
                Pair(box, box)
            }
        }
    }

    /**
     * Calculates the largest box with the same aspect ratio that fits inside the destination.
     */
    fun fitCenter(srcW: Double, srcH: Double, dstW: Double, dstH: Double): Box {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return Box(0.0, 0.0, 0.0, 0.0)
        }
        
        val srcRatio = srcW / srcH
        val dstRatio = dstW / dstH
        
        val newW: Double
        val newH: Double
        
        if (srcRatio > dstRatio) {
            // Source is wider than destination
            newW = dstW
            newH = dstW / srcRatio
        } else {
            // Source is taller than destination
            newH = dstH
            newW = dstH * srcRatio
        }
        
        val left = (dstW - newW) / 2.0
        val top = (dstH - newH) / 2.0
        
        return Box(left, top, left + newW, top + newH)
    }

    /**
     * Determines if a hold gesture should start based on duration and movement.
     */
    fun holdStarts(durationMs: Long, moveDp: Double): Boolean {
        return durationMs >= dk.cbavf.studio.math.MathParams.get("ab.holdMs") && moveDp < dk.cbavf.studio.math.MathParams.get("ab.holdSlopDp")
    }
}
