package dk.cbavf.studio.sequence.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import dk.cbavf.studio.sequence.FrameDecoder
import dk.cbavf.studio.sequence.model.SequenceDoc
import dk.cbavf.studio.sequence.model.SequenceTiming
import dk.cbavf.studio.sequence.model.Span
import dk.cbavf.studio.sequence.model.TimelineIndex
import dk.cbavf.studio.sequence.model.PlayMode
import java.io.File

data class ExportReport(
    val file: File,
    val widthPx: Int,
    val heightPx: Int,
    val frames: Int,
    /** Human-readable caveats (dropped short frames, scaling) to show after export. */
    val notes: List<String>,
)

/**
 * One GIF frame per timeline span (ping-pong and black frames expanded). Spans shorter than the
 * GIF's 1/100 s resolution are merged into their neighbours by the running delay rounding.
 */
fun exportGif(doc: SequenceDoc, decoder: FrameDecoder, file: File, progress: (Float) -> Unit): ExportReport {
    val (w, h) = decoder.frameSize(doc) ?: error("Sequence is empty")
    val spans = doc.timeline()
    val used = spans.map { it.item }.filter { it != Span.BLACK }.distinct()
    val pixels = IntArray(w * h)

    // Pass 1: palette over every frame actually shown.
    val palette = PaletteBuilder()
    used.forEachIndexed { n, item ->
        frame(decoder, doc, item, w, h, pixels)
        palette.add(pixels)
        progress(0.3f * (n + 1) / used.size)
    }
    if (spans.any { it.item == Span.BLACK }) palette.addBlack()
    val colors = palette.build()
    val mapper = PaletteMapper(colors)

    // Pass 2: encode.
    val delays = SequenceTiming.gifDelaysCs(spans, doc.rateHz)
    val indices = ByteArray(w * h)
    var written = 0
    var merged = 0
    var tooFast = 0
    file.outputStream().buffered().use { out ->
        val gif = GifEncoder(out, w, h, colors, loop = doc.mode != PlayMode.Once)
        var shownItem: Int? = null
        spans.forEachIndexed { i, span ->
            val delay = delays[i]
            if (delay == 0) {
                merged++
                return@forEachIndexed
            }
            if (delay < 2) tooFast++
            if (span.item != shownItem) {
                frame(decoder, doc, span.item, w, h, pixels)
                mapper.map(pixels, indices)
                shownItem = span.item
            }
            gif.addFrame(indices, delay)
            written++
            progress(0.3f + 0.7f * (i + 1) / spans.size)
        }
        gif.finish()
    }
    val notes = buildList {
        if (colors.size == 256) add("More than 256 colours: reduced to the 256 most common (no dithering).")
        if (merged > 0) add("$merged frames shorter than 1/100 s merged into their neighbours.")
        if (tooFast > 0) add("$tooFast frames last 1/100 s; many viewers slow those down to 1/10 s.")
    }
    return ExportReport(file, w, h, written, notes)
}

/**
 * H.264 MP4 at a constant [fps], resampled from the sequence rate. Frames are drawn 1:1 into
 * the encoder's input surface; the size is cropped (centred) to what the encoder accepts and
 * only halved if it still refuses.
 */
fun exportMp4(doc: SequenceDoc, decoder: FrameDecoder, file: File, fps: Int, progress: (Float) -> Unit): ExportReport {
    val (srcW, srcH) = decoder.frameSize(doc) ?: error("Sequence is empty")
    val index = TimelineIndex.of(doc)
    val frames = SequenceTiming.videoFrames(index, doc.rateHz, fps)
    val notes = mutableListOf<String>()

    val (w, h, scale) = encoderSize(srcW, srcH)
    if (scale != 1) notes += "Encoder can't take ${srcW}×$srcH; scaled down ×1/$scale (moiré will change)."
    else if (w != srcW || h != srcH) notes += "Cropped to ${w}×$h (encoder size limits)."
    if (fps < doc.rateHz) notes += "Resampled from ${doc.rateHz} Hz to $fps fps: frames shorter than 1/$fps s can be skipped."

    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        // Line patterns are the worst case for a video codec: give it plenty of bits.
        setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps / 8).coerceIn(4_000_000, 40_000_000).toInt())
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = codec.createInputSurface()
    codec.start()
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val drain = Drain(codec, muxer, fps)

    val spans = index.spans
    val src = Rect()
    val dst = Rect(0, 0, w, h)
    var shownItem: Int? = null
    var bitmap: Bitmap? = null
    try {
        frames.forEachIndexed { f, spanIndex ->
            val item = spans[spanIndex].item
            if (item != shownItem) {
                bitmap?.recycle()
                bitmap = if (item == Span.BLACK) null else decoder.decode(doc.items[item], srcW, srcH)
                shownItem = item
            }
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.BLACK)
                bitmap?.let { b ->
                    // Centre crop of w*scale × h*scale source pixels.
                    val cw = w * scale
                    val ch = h * scale
                    src.set((srcW - cw) / 2, (srcH - ch) / 2, (srcW - cw) / 2 + cw, (srcH - ch) / 2 + ch)
                    canvas.drawBitmap(b, src, dst, null)
                }
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            // Keep the encoder at most a couple of frames behind, so the surface never blocks.
            drain.until(f - 2)
            progress((f + 1).toFloat() / frames.size)
        }
        codec.signalEndOfInputStream()
        drain.toEnd()
    } catch (e: Throwable) {
        file.delete()
        throw e
    } finally {
        bitmap?.recycle()
        runCatching { codec.stop() }
        codec.release()
        surface.release()
        runCatching { if (drain.started) muxer.stop() }
        muxer.release()
    }
    if (drain.written != frames.size) notes += "Encoder wrote ${drain.written} of ${frames.size} frames."
    return ExportReport(file, w, h, drain.written, notes)
}

/** Copies encoded frames to the muxer, stamping each with its exact constant-rate time. */
private class Drain(val codec: MediaCodec, val muxer: MediaMuxer, val fps: Int) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    var started = false
    var written = 0

    /** Drains until at least [frame] + 1 frames are written (or the encoder stops producing). */
    fun until(frame: Int) {
        var idle = 0
        while (true) {
            if (!step(TIMEOUT_US)) {
                if (written > frame || ++idle > MAX_IDLE) return
            }
        }
    }

    fun toEnd() {
        var idle = 0
        while (true) {
            val more = step(TIMEOUT_US)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            if (!more && ++idle > MAX_IDLE * 10) return
        }
    }

    /** Handles one encoder event; false when nothing was ready. */
    private fun step(timeoutUs: Long): Boolean {
        val i = codec.dequeueOutputBuffer(info, timeoutUs)
        when {
            i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                started = true
            }
            i >= 0 -> {
                val buffer = codec.getOutputBuffer(i)!!
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!config && info.size > 0 && started) {
                    info.presentationTimeUs = written * 1_000_000L / fps
                    muxer.writeSampleData(track, buffer, info)
                    written++
                }
                codec.releaseOutputBuffer(i, false)
            }
        }
        return true
    }

    companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 100 // × 10 ms
    }
}

/** Largest centred crop the AVC encoder accepts, halving only as a last resort. */
private fun encoderSize(w: Int, h: Int): Triple<Int, Int, Int> {
    val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
        .map { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
    var scale = 1
    while (scale <= 8) {
        val sw = w / scale
        val sh = h / scale
        for (align in intArrayOf(2, 16)) {
            val cw = sw - sw % align
            val ch = sh - sh % align
            if (caps.any { it.isSizeSupported(cw, ch) }) return Triple(cw, ch, scale)
        }
        scale *= 2
    }
    return Triple(w / 2 * 2, h / 2 * 2, 1)
}

/** Fills [out] with the item's pixels, or black. */
private fun frame(decoder: FrameDecoder, doc: SequenceDoc, item: Int, w: Int, h: Int, out: IntArray) {
    if (item == Span.BLACK) {
        out.fill(Color.BLACK)
        return
    }
    val b = decoder.decode(doc.items[item], w, h)
    b.getPixels(out, 0, w, 0, 0, w, h)
    b.recycle()
}
