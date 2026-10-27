package dk.cbavf.studio.camera

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dk.cbavf.studio.compose.android
import dk.cbavf.studio.draw.LayerImages
import dk.cbavf.studio.draw.drawLayers
import dk.cbavf.studio.blend.isSubtract
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Ink
import dk.cbavf.studio.draw.model.Layer
import kotlin.math.roundToInt

/**
 * Everything needed to burn the camera overlay into a capture, taken as a snapshot when the
 * shutter is pressed so that changes during a long burst do not leak in.
 */
class OverlayRender(
    val overlay: Overlay,
    val blend: Blend,
    val opacity: Float,
    val spacing: Float,
    val angle: Float,
    val assetId: String?,
    val drawLayers: List<Layer>,
    val barrierStripPx: Int,
    val barrierFrames: Int,
    /** What the rack cable into the camera's Overlay input carried, if anything. */
    val routed: ImageBitmap?,
    /** The preview box in pixels: the overlay was laid out at this size and is scaled to the capture. */
    val previewWidthPx: Int,
    val previewHeightPx: Int,
    val burnIn: Boolean,
) {
    /** True when there is something to composite and burn-in is on. */
    val burn: Boolean get() = burnIn && (overlay != Overlay.None || routed != null)
}

fun CameraState.overlaySnapshot(src: OverlaySources): OverlayRender = OverlayRender(
    overlay = overlay, blend = overlayBlend, opacity = overlayOpacity,
    spacing = overlaySpacing, angle = overlayAngle, assetId = overlayAssetId,
    drawLayers = src.drawLayers(), barrierStripPx = src.barrierStripPx(), barrierFrames = src.barrierFrames(),
    routed = src.routed(), previewWidthPx = previewWidthPx, previewHeightPx = previewHeightPx, burnIn = burnIn,
)

/**
 * Draws the overlay into a [w] by [h] area. The same code draws the live preview and the capture, so
 * what you line up on screen is what gets burnt in. [images] is async on screen and blocking offscreen.
 */
fun DrawScope.drawOverlay(o: OverlayRender, w: Float, h: Float, images: (String) -> ImageBitmap?) {
    when (o.overlay) {
        Overlay.Grating -> drawLayers(
            listOf(Layer(0, Grating(spacingPx = o.spacing, angleDeg = o.angle), ink = Ink.Black)),
            sheet = false,
            images = images,
        )
        Overlay.Draw -> drawLayers(o.drawLayers, sheet = false, images = images)
        Overlay.Barrier -> {
            val strip = o.barrierStripPx.coerceAtLeast(1).toFloat()
            val n = o.barrierFrames.coerceAtLeast(2)
            var x = 0f
            while (x < w) {
                drawRect(Color.Black, Offset(x + strip, 0f), Size(strip * (n - 1), h))
                x += strip * n
            }
        }
        // Fitted to the preview, centred: line it up with the scene to rephotograph it.
        Overlay.Image -> o.assetId?.let { id -> images(id)?.let { drawFitted(it, w, h) } }
        Overlay.None -> Unit
    }
    // A rack input shows on top, fitted and centred, whatever the overlay setting.
    o.routed?.let { drawFitted(it, w, h) }
}

private fun DrawScope.drawFitted(img: ImageBitmap, w: Float, h: Float) {
    val k = minOf(w / img.width, h / img.height)
    val iw = (img.width * k).toInt()
    val ih = (img.height * k).toInt()
    drawImage(img, dstOffset = IntOffset(((w - iw) / 2).toInt(), ((h - ih) / 2).toInt()), dstSize = IntSize(iw, ih))
}

/**
 * Composites the overlay onto a captured image at its resolution, with the overlay's blend mode and
 * opacity. Returns [base] itself (drawn on) when it is mutable ARGB_8888, otherwise a copy; the
 * caller recycles whichever it does not keep.
 */
fun compositeOverlay(base: Bitmap, o: OverlayRender): Bitmap {
    if (!o.burn) return base
    val pw = if (o.previewWidthPx > 0) o.previewWidthPx.toFloat() else base.width.toFloat()
    val ph = if (o.previewHeightPx > 0) o.previewHeightPx.toFloat() else base.height.toFloat()
    val k = base.width / pw
    val layer = ImageBitmap(base.width, base.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(layer), Size(pw, ph)) {
        scale(k, k, pivot = Offset.Zero) {
            // Offscreen: wait for every image, never burn a placeholder into a saved file.
            drawOverlay(o, pw, ph, LayerImages::blocking)
        }
    }
    val target = if (base.isMutable && base.config == Bitmap.Config.ARGB_8888) base else base.copy(Bitmap.Config.ARGB_8888, true)
    val paint = Paint().apply {
        alpha = (o.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        blendMode = o.blend.android
    }
    val layerBitmap = layer.asAndroidBitmap()
    if (o.blend.isSubtract) {
        dk.cbavf.studio.blend.SubtractMath.blendOnto(target, layerBitmap, o.opacity, reverse = o.blend == Blend.ReverseSubtract)
    } else AndroidCanvas(target).drawBitmap(layerBitmap, 0f, 0f, paint)
    layerBitmap.recycle()
    return target
}
