package dk.cbavf.studio.nodes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dk.cbavf.studio.blend.isSubtract
import dk.cbavf.studio.compose.LayerPixels
import dk.cbavf.studio.compose.android
import dk.cbavf.studio.compose.model.ComposeDoc
import dk.cbavf.studio.compose.renderComposite
import dk.cbavf.studio.nodes.model.ComposeStack
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
    /** Rack inputs of Node Lab: pixels and version. */
    private val module: (String, Int) -> Bitmap? = { _, _ -> null },
    private val moduleVer: (String) -> Int = { 0 },
    /** Rack signal inputs of Node Lab: the number patched in. */
    private val moduleNumber: (String) -> Float = { 0f },
    /** The Compose room's document, and a number that changes with its Rack inputs. */
    private val compose: () -> ComposeDoc? = { null },
    private val composeVer: () -> Int = { 0 },
) : ImageBackend {
    override fun moduleLevel(jack: String): Float = moduleNumber(jack)

    override fun composeDoc(): ComposeDoc? = compose()

    override fun composeVersion(): Int = composeVer()

    /** The Compose Stack node's input images while it draws, by live-layer input name. */
    private val stackImages = HashMap<String, Bitmap>()

    /** Layer pixels for the Compose Stack node: a cache of its own; live layers from its inputs, else the Rack. */
    private val stackPixels by lazy {
        LayerPixels(
            store, lineage,
            routed = { input, maxPx -> stackImages[input]?.let { fitted(it, maxPx) } ?: module(input, maxPx) },
            // A node input's name already names what it carries.
            routeVersion = { input -> if (input.startsWith(ComposeStack.NODE_INPUT)) 0 else moduleVer(input) },
            cacheBytes = 48 * 1024 * 1024,
        )
    }

    // One at a time: the input images are handed to the layer cache through [stackImages].
    @Synchronized
    override fun composite(doc: ComposeDoc, images: Map<String, NodeValue.Img>, maxPx: Int): NodeValue.Img {
        images.forEach { (input, v) -> stackImages[input] = v.handle as Bitmap }
        try {
            val k = minOf(1f, maxPx.toFloat() / maxOf(doc.widthPx, doc.heightPx).coerceAtLeast(1))
            return NodeValue.Img(renderComposite(doc, k, stackPixels), doc.widthPx, doc.heightPx)
        } finally {
            stackImages.clear()
        }
    }

    private fun fitted(bmp: Bitmap, maxPx: Int): Bitmap {
        val k = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
        return if (k >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
    }

    override fun moduleVersion(jack: String): Int = moduleVer(jack)

    override fun moduleInput(jack: String, maxPx: Int): NodeValue.Img? =
        module(jack, maxPx)?.let { NodeValue.Img(it, it.width, it.height) }

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

    private fun channelSpace(name: String?): dk.cbavf.studio.channels.ColorSpace =
        dk.cbavf.studio.channels.ColorSpace.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: dk.cbavf.studio.channels.ColorSpace.RGBA

    override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img? {
        val a = inputs[0] as? NodeValue.Img ?: return null
        val src = a.handle as Bitmap
        val scale = src.width.toFloat() / a.width
        fun filter(f: LayerFilter) = applyFilters(src, listOf(f), scale)
        val out: Bitmap = when (type) {
            "chsplit" -> {
                val space = channelSpace(props["Space"])
                val channel = (props["Channel"]?.firstOrNull()?.digitToIntOrNull() ?: 1) - 1
                val w = src.width
                val h = src.height
                val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dk.cbavf.studio.channels.ChannelPlanes.split(space, channel, px), 0, w, 0, 0, w, h) }
            }
            "chmix" -> {
                val setup = dk.cbavf.studio.channels.ChannelSetup(
                    space = channelSpace(props["Space"]),
                    gains = listOf(nums[1], nums[2], nums[3], nums[4]),
                    offsets = listOf(nums[5], nums[6], nums[7], nums[8]),
                )
                val w = src.width
                val h = src.height
                val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(setup.apply(px), 0, w, 0, 0, w, h) }
            }
            "chmerge" -> {
                val space = channelSpace(props["Space"])
                val w = src.width
                val h = src.height
                val planes = (0 until space.channelNames.size).map { i ->
                    (inputs.getOrNull(i) as? NodeValue.Img)?.let { v ->
                        val b = v.handle as Bitmap
                        val fitted = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
                        IntArray(w * h).also { fitted.getPixels(it, 0, w, 0, 0, w, h) }
                    }
                }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dk.cbavf.studio.channels.ChannelPlanes.merge(space, planes, w * h), 0, w, 0, 0, w, h) }
            }
            "fmamimage" -> {
                val w = src.width
                val h = src.height
                val down = props["Direction"] != "Across"
                val setup = dk.cbavf.studio.math.FmAmSetup(
                    Evaluator.fmAmMode(props["Mode"]), nums[1].toDouble(), nums[2].toDouble(), nums[3].toDouble(), nums[4].toDouble(), nums[5].toDouble(),
                )
                val wave = dk.cbavf.studio.math.FmAm.profile(if (down) h else w, setup)
                val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
                val done = if (props["Effect"] == "Shift") dk.cbavf.studio.math.FmAmImage.shift(px, w, h, wave, down, nums[7] * scale)
                else dk.cbavf.studio.math.FmAmImage.brightness(px, w, h, wave, down, nums[6])
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(done, 0, w, 0, 0, w, h) }
            }
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
                    val mode = runCatching { Blend.valueOf(props["Blend"] ?: "Normal") }.getOrDefault(Blend.Normal)
                    if (mode.isSubtract) {
                        // B fitted into A's frame on its own, then subtracted pixel by pixel.
                        val fitted = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
                        Canvas(fitted).drawBitmap(bb, m, Paint(Paint.FILTER_BITMAP_FLAG))
                        dk.cbavf.studio.blend.SubtractMath.blendOnto(out, fitted, nums[2], reverse = mode == Blend.ReverseSubtract)
                        fitted.recycle()
                    } else Canvas(out).drawBitmap(bb, m, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (nums[2].coerceIn(0f, 1f) * 255).toInt()
                        blendMode = mode.android
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
