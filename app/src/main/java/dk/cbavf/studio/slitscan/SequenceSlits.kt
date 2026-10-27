package dk.cbavf.studio.slitscan

/**
 * Which Sequence item gives each slit-scan frame. A timeline can be read one frame per item, or by its timing, where an item
 * held for longer gives more frames (the timeline sampled at even steps). Only the first [MAX_ITEMS] items are used, since
 * every item used is a full picture held in memory.
 */
object SequenceSlits {
    /** Items read from a timeline at most (each one is decoded once, however many frames it gives). */
    const val MAX_ITEMS = 32

    /** Frames taken from a timeline by its timing at most. */
    const val MAX_FRAMES = 240

    /**
     * The item index of each frame, in order. [ticks] are the items' durations (anything under 1 counts as 1). [byTime] false
     * gives one frame per item; true samples the timeline at up to [maxFrames] even steps.
     */
    fun frameOrder(ticks: List<Int>, byTime: Boolean, maxFrames: Int = MAX_FRAMES): List<Int> {
        val used = ticks.take(MAX_ITEMS).map { it.coerceAtLeast(1) }
        if (used.isEmpty() || maxFrames < 1) return emptyList()
        if (!byTime) return used.indices.take(maxFrames)
        val total = used.fold(0L) { a, t -> a + t }
        val count = minOf(total, maxFrames.toLong()).toInt()
        val out = ArrayList<Int>(count)
        var item = 0
        var end = used[0].toLong()
        for (k in 0 until count) {
            // The tick at this step's start; items are walked forward only, as the steps only grow.
            val t = k * total / count
            while (t >= end) { item++; end += used[item] }
            out += item
        }
        return out
    }

    /** The items a frame order needs decoded, each once, in order. */
    fun itemsUsed(order: List<Int>): List<Int> = order.distinct().sorted()
}
