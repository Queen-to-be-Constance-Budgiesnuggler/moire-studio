package dk.cbavf.studio.sequence

import dk.cbavf.studio.sequence.export.GifEncoder
import dk.cbavf.studio.sequence.export.PaletteBuilder
import dk.cbavf.studio.sequence.export.PaletteMapper
import dk.cbavf.studio.sequence.model.AssetSource
import dk.cbavf.studio.sequence.model.FrameFit
import dk.cbavf.studio.sequence.model.FrameGeometry
import dk.cbavf.studio.sequence.model.FrameShape
import dk.cbavf.studio.sequence.model.PlayMode
import dk.cbavf.studio.sequence.model.SeqItem
import dk.cbavf.studio.sequence.model.SequenceDoc
import dk.cbavf.studio.sequence.model.SequenceTiming
import dk.cbavf.studio.sequence.model.Span
import dk.cbavf.studio.sequence.model.TimelineIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

class SequenceTest {
    private fun seq(vararg ticks: Int, mode: PlayMode = PlayMode.Loop, black: Int = 0) =
        SequenceDoc(ticks.mapIndexed { i, t -> SeqItem(AssetSource("a$i"), t) }, 60, mode, black)

    @Test
    fun frameShapesUseTheScreensLongSide() {
        val screen = 1080 to 2400
        assertEquals(1080 to 2400, FrameGeometry.frameSize(FrameShape.Screen, screen, 4032 to 3024))
        assertEquals(4032 to 3024, FrameGeometry.frameSize(FrameShape.First, screen, 4032 to 3024))
        assertEquals(2400 to 1350, FrameGeometry.frameSize(FrameShape.R16x9, screen, null))
        assertEquals(1800 to 2400, FrameGeometry.frameSize(FrameShape.R3x4, screen, null))
        assertEquals(2400 to 2400, FrameGeometry.frameSize(FrameShape.Square, screen, null))
    }

    @Test
    fun picturesFitFillOrStayExact() {
        // A landscape photo in a portrait screen frame.
        val fit = FrameGeometry.place(4000, 3000, 0, 1080, 2400, FrameFit.Fit)
        assertEquals(1080f / 4000, fit.scale, 1e-6f)
        val fill = FrameGeometry.place(4000, 3000, 0, 1080, 2400, FrameFit.Fill)
        assertEquals(2400f / 3000, fill.scale, 1e-6f)
        // Turned a quarter, it fills the portrait frame much better when fitted.
        val turned = FrameGeometry.place(4000, 3000, 1, 1080, 2400, FrameFit.Fit)
        assertEquals(minOf(1080f / 3000, 2400f / 4000), turned.scale, 1e-6f)
        assertEquals(1f, FrameGeometry.place(4000, 3000, 0, 1080, 2400, FrameFit.Exact).scale, 0f)
        assertEquals(540f, fit.centerX, 0f)
    }

    @Test
    fun canvasesDefaultToExactAndImagesToFit() {
        assertEquals(FrameFit.Fit, SeqItem(AssetSource("a")).effectiveFit)
        val canvas = SeqItem(dk.cbavf.studio.sequence.model.CanvasSource(dk.cbavf.studio.library.CanvasDoc(10, 10, emptyList())))
        assertEquals(FrameFit.Exact, canvas.effectiveFit)
        assertEquals(FrameFit.Fill, canvas.copy(fit = FrameFit.Fill).effectiveFit)
    }

    @Test
    fun pingPongDoesNotRepeatEndFrames() {
        assertEquals(listOf(0, 1, 2, 3, 2, 1), seq(1, 1, 1, 1, mode = PlayMode.PingPong).timeline().map { it.item })
    }

    @Test
    fun blackFramesFollowEveryItem() {
        val spans = seq(2, 3, black = 1).timeline()
        assertEquals(listOf(Span(0, 2), Span(Span.BLACK, 1), Span(1, 3), Span(Span.BLACK, 1)), spans)
    }

    @Test
    fun indexFindsSpanAndLoops() {
        val index = TimelineIndex.of(seq(2, 3))
        assertEquals(5, index.totalTicks)
        assertEquals(listOf(0, 0, 1, 1, 1, 0), (0L..5L).map { index.spanAt(it) })
    }

    @Test
    fun onceEnds() {
        val index = TimelineIndex.of(seq(2, 3, mode = PlayMode.Once))
        assertEquals(1, index.spanAt(4))
        assertNull(index.spanAt(5))
    }

    @Test
    fun gifDelaysKeepTotalExact() {
        // 120 Hz, one tick each: 0.83 cs per frame. Running rounding keeps 12 frames at 10 cs.
        val spans = List(12) { Span(it, 1) }
        val delays = SequenceTiming.gifDelaysCs(spans, 120)
        assertEquals(10, delays.sum())
        assertEquals(listOf(5, 5), SequenceTiming.gifDelaysCs(listOf(Span(0, 3), Span(1, 3)), 60))
    }

    @Test
    fun videoResamplesToOutputRate() {
        // 120 Hz sequence of 2-tick frames played back as 60 fps video: one video frame each.
        val doc = SequenceDoc(List(4) { SeqItem(AssetSource("a$it"), 2) }, rateHz = 120)
        assertEquals(listOf(0, 1, 2, 3), SequenceTiming.videoFrames(TimelineIndex.of(doc), 120, 60))
    }

    @Test
    fun gifRoundTripsThroughImageIo() {
        val w = 97
        val h = 61
        val rnd = Random(1)
        val colors = intArrayOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FFFF)
        val a = IntArray(w * h) { colors[rnd.nextInt(colors.size)] }
        // Second frame: one changed block, so it is written as a cropped frame.
        val b = a.copyOf().also { for (y in 10 until 20) for (x in 30 until 45) it[y * w + x] = 0xFF0000 }

        val palette = PaletteBuilder().apply { add(a); add(b) }.build()
        assertEquals(4, palette.size)
        val mapper = PaletteMapper(palette)
        val bytes = ByteArrayOutputStream().use { out ->
            val gif = GifEncoder(out, w, h, palette, loop = true)
            for (f in listOf(a, b)) gif.addFrame(ByteArray(w * h).also { mapper.map(f, it) }, 7)
            gif.finish()
            out.toByteArray()
        }

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(2, reader.getNumImages(true))
        val first = reader.read(0)
        for (y in 0 until h) for (x in 0 until w) assertEquals(a[y * w + x], first.getRGB(x, y) and 0xFFFFFF)
        val second = reader.read(1)
        assertEquals(15, second.width)
        assertEquals(10, second.height)
        for (y in 0 until 10) for (x in 0 until 15) assertEquals(0xFF0000, second.getRGB(x, y) and 0xFFFFFF)
    }

    @Test
    fun lzwSurvivesDictionaryResets() {
        // 256 noisy colours over many pixels forces several table clears.
        val w = 400
        val h = 300
        val rnd = Random(2)
        val palette = IntArray(256) { it * 0x010101 }
        val idx = ByteArray(w * h) { rnd.nextInt(256).toByte() }
        val bytes = ByteArrayOutputStream().use { out ->
            GifEncoder(out, w, h, palette, loop = false).apply { addFrame(idx, 10); finish() }
            out.toByteArray()
        }
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        for (i in idx.indices) assertEquals(palette[idx[i].toInt() and 0xFF], img.getRGB(i % w, i / w) and 0xFFFFFF)
    }
}
