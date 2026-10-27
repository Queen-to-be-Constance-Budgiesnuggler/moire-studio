package dk.cbavf.studio.draw

/** What to paint this frame; the renderer ([LayerCache]) carries it out. Layer indices count from the bottom. */
sealed interface CachePlan {
    /** Draw every layer straight to the screen, as without a cache. */
    data object Live : CachePlan

    /** The full picture is up to date: just draw it. */
    data object Full : CachePlan

    /** New layers on top: paint layers from [from] on into the full picture, then draw it. */
    data class ExtendFull(val from: Int) : CachePlan

    /**
     * Paint the full picture again: from a blank sheet when [from] is 0, otherwise from the base picture (which holds the
     * layers below [from]) plus the layers from [from] on. Then draw it.
     */
    data class RebuildFull(val from: Int) : CachePlan

    /**
     * Layers are still changing. The base picture is made to hold the layers below [upTo]: from a blank sheet when [from] is 0,
     * otherwise by painting layers [from] to [upTo] onto what it holds (nothing when they are equal). Draw it, then draw the
     * layers from [upTo] on live.
     */
    data class Base(val from: Int, val upTo: Int) : CachePlan
}

/**
 * Decides, frame by frame, how much of a layered canvas to paint again. Two cached pictures hold finished layers: the full
 * picture (every layer) and the base picture (the layers below the one being edited). The planner only keeps track of which
 * layers each picture holds, by key; the renderer does what the plan says.
 *
 * - Nothing changed: draw the full picture (a stroke being drawn, the selection and the fringes go on top).
 * - Layers added on top: paint only those onto the full picture.
 * - A layer changing frame after frame (a slider, a move): the layers below it come from the base picture, the rest is live.
 * - Once the layers stop changing for a frame, they are baked into the full picture again.
 * - A new canvas size or newly loaded images ([plan]'s context) throw both pictures away.
 *
 * Keys are compared with ==, so a layer that changes in any way counts as a new one. A subtracting layer can only be painted
 * from a blank sheet up, never on top of a picture: when one would have to be, the plan falls back to painting from scratch.
 * The lists passed in must not change afterwards.
 */
class LayerCachePlanner<K>(private val subtracts: (K) -> Boolean) {
    private var full: List<K>? = null
    private var base: List<K>? = null
    private var last: List<K>? = null
    private var context: Any? = null

    /** The layers each picture holds, bottom first; null if it holds nothing usable. For tests and the debug readout. */
    val fullKeys: List<K>? get() = full
    val baseKeys: List<K>? get() = base

    /** Forget both pictures, as if the canvas were new. */
    fun reset() {
        full = null
        base = null
        last = null
        context = null
    }

    fun plan(keys: List<K>, context: Any): CachePlan {
        if (context != this.context) {
            reset()
            this.context = context
        }
        val previous = last
        last = keys
        val f = full
        if (f != null && f == keys) return CachePlan.Full
        if (f != null && isPrefix(f, keys) && canPaintOnTop(keys, f.size, keys.size)) {
            full = keys
            return CachePlan.ExtendFull(f.size)
        }
        val b = base?.takeIf { isPrefix(it, keys) }
        if (previous == keys) {
            // Settled: bake every layer into the full picture, starting from the base if it helps.
            full = keys
            return if (b != null && canPaintOnTop(keys, b.size, keys.size)) CachePlan.RebuildFull(b.size) else CachePlan.RebuildFull(0)
        }
        // Still changing: the layers below the first change go into the base picture, the rest is drawn live.
        val upTo = if (previous == null) 0 else firstDifference(previous, keys)
        if (upTo == 0 || !canPaintOnTop(keys, upTo, keys.size)) return CachePlan.Live
        val plan = if (b != null && b.size <= upTo && canPaintOnTop(keys, b.size, upTo)) {
            CachePlan.Base(b.size, upTo)
        } else {
            CachePlan.Base(0, upTo)
        }
        base = keys.take(upTo)
        return plan
    }

    /** True if no layer in [from] until [to] subtracts, so they can be painted on top of a picture. */
    private fun canPaintOnTop(keys: List<K>, from: Int, to: Int) = (from until to).none { subtracts(keys[it]) }

    companion object {
        /** True if [a] is [b]'s first layers (or all of them). */
        fun <K> isPrefix(a: List<K>, b: List<K>): Boolean = a.size <= b.size && a.indices.all { a[it] == b[it] }

        /** The index of the first layer that differs; the shorter length if one list starts the other. */
        fun <K> firstDifference(a: List<K>, b: List<K>): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) if (a[i] != b[i]) return i
            return n
        }
    }
}
