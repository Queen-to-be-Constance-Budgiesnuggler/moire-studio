package dk.cbavf.studio.nodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dk.cbavf.studio.compose.android
import dk.cbavf.studio.compose.applyFilters
import dk.cbavf.studio.compose.applyLut
import dk.cbavf.studio.compose.model.LayerFilter
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.fft.model.FilterShape
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.renderBitmap
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.FrameRef
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.NodeValue

/**
 * Node image work on Android bitmaps. An image value's width/height are its full (logical) size;
 * the bitmap itself can be smaller (previews), and sizes given in pixels (blur radius, offsets)
 * are scaled to it, so a preview looks like the full render.
 */
class AndroidImageBackend(
    private val store: ProjectStore,
    private val lineage: () -> Lineage,
    private val draw: () -> CanvasDoc?,
    private val roomFrames: (String) -> List<FrameRef> = { emptyList() },
    /** The linked phone's latest picture and how many have come. */
    private val remote: () -> Pair<Bitmap?, Int> = { null to 0 },
) : ImageBackend {
    override fun frames(source: String): List<FrameRef> = roomFrames(source)

    override fun remoteCount(): Int = remote().second

    override fun remoteImage(maxPx: Int): NodeValue.Img? {
        val bmp = remote().first ?: return null
        val k = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (k >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        return NodeValue.Img(out, bmp.width, bmp.height)
    }

    override fun libraryImage(assetId: String, maxPx: Int): NodeValue.Img? {
        val rec = lineage()[assetId] ?: return null
        val bmp = decodeAsset(store, rec, maxPx) ?: return null
        return NodeValue.Img(bmp, rec.widthPx.coerceAtLeast(1), rec.heightPx.coerceAtLeast(1))
    }

    override fun drawCanvas(): CanvasDoc? = draw()?.takeIf { it.widthPx > 0 }

    override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int): NodeValue.Img {
        // Render at the reduced size directly when previewing (drawn scaled, so spacing keeps its look).
        val k = minOf(1f, maxPx.toFloat() / maxOf(width, height))
        val bmp = renderBitmap(doc, sheet = paper, scale = k)
        return NodeValue.Img(bmp, width, height)
    }

    override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img? {
        val a = inputs[0] as? NodeValue.Img ?: return null
        val src = a.handle as Bitmap
        val scale = src.width.toFloat() / a.width
        fun filter(f: LayerFilter) = applyFilters(src, listOf(f), scale)
        val out: Bitmap = when (type) {
            "develop" -> applyLut(src, Lut.build(DevelopDoc(exposureEv = nums[1], temperature = nums[2], tint = nums[3], black = nums[4], white = nums[5], gamma = nums[6])))
            "invert" -> filter(LayerFilter.Invert)
            "desaturate" -> filter(LayerFilter.Desaturate)
            "posterize" -> filter(LayerFilter.Posterize(nums[1].toInt()))
            "threshold" -> filter(LayerFilter.Threshold(nums[1]))
            "blur" -> filter(LayerFilter.Blur(nums[1]))
            "sharpen" -> filter(LayerFilter.Sharpen(nums[1], nums[2]))
            "noise" -> filter(LayerFilter.Noise(nums[1], nums[2].toInt()))
            "fft" -> filter(LayerFilter.FftPass(runCatching { FilterShape.valueOf(props["Filter"] ?: "LowPass") }.getOrDefault(FilterShape.LowPass), nums[1], nums[2]))
            "glitch" -> filter(LayerFilter.Glitch(GlitchDoc(
                bytesPerPixel = props["Bytes/px"]?.toIntOrNull() ?: 3,
                widthDelta = (nums[1] * scale).toInt(),
                strideDelta = (nums[2] * scale).toInt(),
                chromaX = (nums[3] * scale).toInt(),
                generations = nums[4].toInt(),
            )))
            "mix" -> {
                val b = inputs[1] as? NodeValue.Img
                val out = src.copy(Bitmap.Config.ARGB_8888, true)
                if (b != null) {
                    val bb = b.handle as Bitmap
                    // B fitted into A's frame, centred.
                    val k = minOf(out.width.toFloat() / bb.width, out.height.toFloat() / bb.height)
                    val m = Matrix().apply {
                        postScale(k, k)
                        postTranslate((out.width - bb.width * k) / 2, (out.height - bb.height * k) / 2)
                    }
                    Canvas(out).drawBitmap(bb, m, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (nums[2].coerceIn(0f, 1f) * 255).toInt()
                        blendMode = runCatching { Blend.valueOf(props["Blend"] ?: "Normal") }.getOrDefault(Blend.Normal).android
                    })
                }
                out
            }
            "transform" -> {
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val m = Matrix().apply {
                    postTranslate(-src.width / 2f, -src.height / 2f)
                    postScale(nums[3], nums[3])
                    postRotate(nums[4])
                    postTranslate(src.width / 2f + nums[1] * scale, src.height / 2f + nums[2] * scale)
                }
                Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                out
            }
            else -> return null
        }
        return NodeValue.Img(out, a.width, a.height)
    }
}

/** What the backdrop shows for a value: an image as it is, a canvas rendered on paper at its size. */
fun AndroidImageBackend.previewOf(v: NodeValue?, maxPx: Int): Bitmap? = when (v) {
    is NodeValue.Img -> v.handle as Bitmap
    is NodeValue.Cnv -> {
        val w = v.doc.widthPx.takeIf { it > 0 } ?: Evaluator.DEFAULT_W
        val h = v.doc.heightPx.takeIf { it > 0 } ?: Evaluator.DEFAULT_H
        render(v.doc.copy(widthPx = w, heightPx = h), w, h, paper = true, maxPx).handle as Bitmap
    }
    else -> null
}
