package dk.cbavf.studio.sequence.model

import dk.cbavf.studio.library.CanvasDoc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** Where a sequence frame's pixels come from. */
@Serializable
sealed interface SeqSource

/** A Library image, by asset id. */
@Serializable
@SerialName("asset")
data class AssetSource(val assetId: String) : SeqSource

/** A canvas recipe (e.g. an Animate frame), rendered when the sequence is decoded. */
@Serializable
@SerialName("canvas")
data class CanvasSource(val doc: CanvasDoc) : SeqSource

/** How an item's picture is placed in the sequence frame. */
enum class FrameFit(val label: String) {
    /** Whole picture visible, letterboxed on black. */
    Fit("Fit"),

    /** Frame filled, the overhang cropped. */
    Fill("Fill"),

    /** One picture pixel per frame pixel, centred (moiré stays exact). */
    Exact("1:1"),
}

/** Shape of every frame of a sequence: the projector's screen, the first item, or a fixed aspect. */
enum class FrameShape(val label: String, val ratio: Float?) {
    Screen("Screen", null),
    First("First item", null),
    Square("1:1", 1f),
    R4x3("4:3", 4f / 3),
    R3x4("3:4", 3f / 4),
    R16x9("16:9", 16f / 9),
    R9x16("9:16", 9f / 16),
}

/** One timeline entry, shown for [ticks] display refreshes at the sequence rate. */
@Serializable
data class SeqItem(
    val source: SeqSource,
    val ticks: Int = 1,
    /** Null = automatic: canvases 1:1 (they are screen-sized and pixel-exact), images Fit. */
    val fit: FrameFit? = null,
    /** Quarter turns clockwise, 0..3 (a landscape photo turned to fill a portrait screen). */
    val quarterTurns: Int = 0,
) {
    val effectiveFit: FrameFit get() = fit ?: if (source is CanvasSource) FrameFit.Exact else FrameFit.Fit
}

/** Where a picture lands in a frame: its scale and the frame point its centre goes to. */
data class Placement(val scale: Float, val centerX: Float, val centerY: Float, val quarterTurns: Int)

object FrameGeometry {
    /**
     * Frame size for [shape]. Fixed aspects use the screen's long side as their long side, so
     * nothing is upscaled more than the screen needs. Sizes are even (video encoders want that).
     */
    fun frameSize(shape: FrameShape, screen: Pair<Int, Int>, first: Pair<Int, Int>?): Pair<Int, Int> {
        val long = maxOf(screen.first, screen.second)
        val (w, h) = when {
            shape == FrameShape.Screen -> screen
            shape == FrameShape.First -> first ?: screen
            shape.ratio!! >= 1f -> long to (long / shape.ratio).toInt()
            else -> (long * shape.ratio).toInt() to long
        }
        return (w / 2 * 2).coerceAtLeast(2) to (h / 2 * 2).coerceAtLeast(2)
    }

    /** Placement of a [srcW]×[srcH] picture, turned [quarterTurns], in a [frameW]×[frameH] frame. */
    fun place(srcW: Int, srcH: Int, quarterTurns: Int, frameW: Int, frameH: Int, fit: FrameFit): Placement {
        val turned = quarterTurns.mod(4) % 2 == 1
        val w = if (turned) srcH else srcW
        val h = if (turned) srcW else srcH
        val k = when (fit) {
            FrameFit.Fit -> minOf(frameW.toFloat() / w, frameH.toFloat() / h)
            FrameFit.Fill -> maxOf(frameW.toFloat() / w, frameH.toFloat() / h)
            FrameFit.Exact -> 1f
        }
        return Placement(k, frameW / 2f, frameH / 2f, quarterTurns.mod(4))
    }
}

enum class PlayMode { Loop, PingPong, Once }

/** One stretch of the expanded timeline: an item index, or [BLACK], for [ticks] refreshes. */
data class Span(val item: Int, val ticks: Int) {
    companion object {
        const val BLACK = -1
    }
}

/**
 * A timeline of frames with durations counted in refreshes of a fixed rate, so playback can
 * lock to the display and every frame lasts an exact number of vsyncs.
 */
@Serializable
data class SequenceDoc(
    val items: List<SeqItem> = emptyList(),
    val rateHz: Int = 60,
    val mode: PlayMode = PlayMode.Loop,
    /** Black-frame insertion: black refreshes after every item (0 = off). */
    val blackTicks: Int = 0,
    val shape: FrameShape = FrameShape.Screen,
) {
    /**
     * One pass of playback with ping-pong and black frames expanded. Ping-pong runs
     * 0..n−1 then n−2..1, so looping it never shows an end frame twice in a row.
     */
    fun timeline(): List<Span> {
        val order = when {
            mode == PlayMode.PingPong && items.size > 2 -> items.indices + (items.size - 2 downTo 1)
            else -> items.indices.toList()
        }
        return order.flatMap { i ->
            val item = Span(i, items[i].ticks.coerceAtLeast(1))
            if (blackTicks > 0) listOf(item, Span(Span.BLACK, blackTicks)) else listOf(item)
        }
    }

    fun msPerTick(): Double = 1000.0 / rateHz
}

/** Fast lookup of which span is showing at a given tick. */
class TimelineIndex(val spans: List<Span>, private val loop: Boolean) {
    /** Tick at which each span starts, plus the total at the end. */
    private val starts = LongArray(spans.size + 1).also { s ->
        for (i in spans.indices) s[i + 1] = s[i] + spans[i].ticks
    }

    val totalTicks: Long get() = starts.last()

    /** Index into [spans] showing at [tick], or null once a non-looping timeline has ended. */
    fun spanAt(tick: Long): Int? {
        if (spans.isEmpty()) return null
        val t = if (loop) Math.floorMod(tick, totalTicks) else tick
        if (t < 0 || t >= totalTicks) return null
        // Last start <= t.
        var lo = 0
        var hi = spans.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        fun of(doc: SequenceDoc) = TimelineIndex(doc.timeline(), loop = doc.mode != PlayMode.Once)
    }
}

object SequenceTiming {
    /**
     * GIF delays in centiseconds per span. Rounds the running end time, not each span, so the
     * total stays exact and errors never accumulate.
     */
    fun gifDelaysCs(spans: List<Span>, rateHz: Int): List<Int> {
        var ticks = 0L
        var previousCs = 0L
        return spans.map { s ->
            ticks += s.ticks
            val endCs = (ticks * 100.0 / rateHz).roundToLong()
            (endCs - previousCs).toInt().also { previousCs = endCs }
        }
    }

    /** Spans per output frame for constant-rate video at [fps] (resampled from the sequence rate). */
    fun videoFrames(index: TimelineIndex, rateHz: Int, fps: Int): List<Int> {
        val total = index.totalTicks
        val frames = (total * fps.toDouble() / rateHz).roundToLong().coerceAtLeast(1)
        return List(frames.toInt()) { f ->
            val tick = (f * rateHz.toDouble() / fps).toLong().coerceAtMost(total - 1)
            index.spanAt(tick) ?: 0
        }
    }
}
