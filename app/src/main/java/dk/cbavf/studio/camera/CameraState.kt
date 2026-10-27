package dk.cbavf.studio.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.camera.model.CaptureInfo
import dk.cbavf.studio.camera.model.StackMode
import dk.cbavf.studio.camera.model.WhiteBalance
import dk.cbavf.studio.debug.Debug
import dk.cbavf.studio.draw.model.Blend
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import dk.cbavf.studio.library.exportToMedia
import java.io.File

/** What the preview shows on top of the camera image; burnt into the capture when [CameraState.burnIn] is on. */
enum class Overlay { None, Grating, Draw, Barrier, Image }

enum class StackKind { Additive, Mean, Interlace }

/** Camera room settings; everything here is recorded with each capture. */
class CameraState {
    var cameras by mutableStateOf<List<CameraSpec>>(emptyList())
    var cameraId by mutableStateOf<String?>(null)

    var manualExposure by mutableStateOf(false)
    var exposureNs by mutableStateOf(10_000_000L)
    var iso by mutableIntStateOf(100)
    var manualFocus by mutableStateOf(false)
    var focusDiopters by mutableFloatStateOf(0f)
    var aperture by mutableStateOf<Float?>(null)
    var whiteBalance by mutableStateOf(WhiteBalance.Auto)
    var torch by mutableStateOf(false)
    /** Zoom ratio (1 = the lens as it is); pinch the preview or type it. */
    var zoom by mutableFloatStateOf(1f)

    var burst by mutableIntStateOf(1)
    var stackKind by mutableStateOf(StackKind.Additive)
    var gain by mutableFloatStateOf(0.5f)
    var rowsPerFrame by mutableFloatStateOf(64f)
    var raw by mutableStateOf(false)
    /** Half resolution: 1/4 the pixels, for long bursts on a phone with a small heap. */
    var half by mutableStateOf(false)

    var overlay by mutableStateOf(Overlay.None)

    /** Library image shown by the Image overlay (Library → Use in Camera). */
    var overlayAssetId by mutableStateOf<String?>(null)
    var overlaySpacing by mutableFloatStateOf(12f)
    var overlayAngle by mutableFloatStateOf(0f)
    var overlayOpacity by mutableFloatStateOf(0.6f)

    /** How the overlay is blended onto the capture. The live preview always shows plain alpha. */
    var overlayBlend by mutableStateOf(Blend.Normal)

    /** Composite the overlay into the saved image (otherwise it is only recorded in the capture info). */
    var burnIn by mutableStateOf(true)

    /** Size of the preview box in pixels, so the overlay lines up at the capture's resolution. */
    var previewWidthPx by mutableIntStateOf(0)
    var previewHeightPx by mutableIntStateOf(0)

    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    val spec: CameraSpec? get() = cameras.firstOrNull { it.id == cameraId }

    val settings: CameraSettings
        get() {
            val s = spec
            return CameraSettings(
                exposureNs = exposureNs.takeIf { manualExposure && s?.manualSensor == true },
                iso = iso.takeIf { manualExposure && s?.manualSensor == true },
                focusDiopters = focusDiopters.takeIf { manualFocus },
                aperture = aperture,
                whiteBalance = whiteBalance,
                torch = torch,
                zoom = s?.zoomRange?.let { zoom.coerceIn(it.start, it.endInclusive) } ?: 1f,
            )
        }

    val stackMode: StackMode?
        get() = if (burst <= 1) null else when (stackKind) {
            StackKind.Additive -> StackMode.Additive(gain)
            StackKind.Mean -> StackMode.Mean
            StackKind.Interlace -> StackMode.Interlace(rowsPerFrame)
        }

    fun info(projectedAssetId: String? = null): CaptureInfo {
        val s = settings
        return CaptureInfo(
            cameraId = cameraId ?: "?",
            exposureNs = s.exposureNs,
            iso = s.iso,
            focusDiopters = s.focusDiopters,
            aperture = s.aperture,
            whiteBalance = s.whiteBalance,
            torch = s.torch,
            zoom = s.zoom,
            burst = burst,
            stack = stackMode,
            overlay = overlay.takeIf { it != Overlay.None }?.name?.lowercase(),
            overlayBurned = burnIn && overlay != Overlay.None,
            overlayBlend = overlayBlend.name.takeIf { overlay != Overlay.None },
            overlayOpacity = overlayOpacity.takeIf { overlay != Overlay.None },
            overlaySpacing = overlaySpacing.takeIf { overlay == Overlay.Grating },
            overlayAngle = overlayAngle.takeIf { overlay == Overlay.Grating },
            overlayAssetId = overlayAssetId.takeIf { overlay == Overlay.Image },
            projectedAssetId = projectedAssetId,
        )
    }

    /** Restores settings from a capture (Library → Open in Camera). */
    fun restore(info: CaptureInfo) {
        if (cameras.any { it.id == info.cameraId }) cameraId = info.cameraId
        manualExposure = info.exposureNs != null && info.iso != null
        info.exposureNs?.let { exposureNs = it }
        info.iso?.let { iso = it }
        manualFocus = info.focusDiopters != null
        info.focusDiopters?.let { focusDiopters = it }
        aperture = info.aperture
        whiteBalance = info.whiteBalance
        torch = info.torch
        zoom = info.zoom
        burst = info.burst
        when (val m = info.stack) {
            is StackMode.Additive -> { stackKind = StackKind.Additive; gain = m.gain }
            StackMode.Mean -> stackKind = StackKind.Mean
            is StackMode.Interlace -> { stackKind = StackKind.Interlace; rowsPerFrame = m.rowsPerFrame }
            null -> Unit
        }
        // The overlay comes back too, so a capture can be shot again with the same overlay.
        info.overlay?.let { name -> Overlay.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { overlay = it } }
        info.overlayBlend?.let { name -> Blend.entries.firstOrNull { it.name == name }?.let { overlayBlend = it } }
        info.overlayOpacity?.let { overlayOpacity = it }
        info.overlaySpacing?.let { overlaySpacing = it }
        info.overlayAngle?.let { overlayAngle = it }
        info.overlayAssetId?.let { overlayAssetId = it }
        burnIn = info.overlayBurned
    }
}

/** A developed capture: upright, stacked, JPEG-encoded. */
class Developed(val jpeg: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * Rotates and stacks the shots of one capture into a single JPEG. With an [overlay] snapshot the
 * overlay is composited on the stacked image, with its blend mode and opacity, before encoding.
 */
fun develop(spec: CameraSpec, shots: List<Shot>, stackMode: StackMode?, half: Boolean, overlay: OverlayRender? = null): Developed {
    val front = spec.facing == CameraCharacteristics.LENS_FACING_FRONT
    val sample = if (half) 2 else 1
    val frames = shots.map { decodeUpright(it.jpeg, spec.sensorOrientation, front, sample) }
    val stacked = if (frames.size == 1 || stackMode == null) frames.first() else stack(frames, stackMode)
    frames.forEach { if (it !== stacked) it.recycle() }
    val image = if (overlay != null && overlay.burn) compositeOverlay(stacked, overlay) else stacked
    Debug.t("camera", "developed ${stacked.width}x${stacked.height} from ${shots.size} shot(s)" +
        if (overlay != null && overlay.burn) ", overlay ${overlay.overlay.name} burnt in with ${overlay.blend.name} at ${(overlay.opacity * 100).toInt()} %" else "")
    if (image !== stacked) stacked.recycle()
    val out = java.io.ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.JPEG, 95, out)
    return Developed(out.toByteArray(), image.width, image.height).also { image.recycle() }
}

/**
 * Files a developed JPEG as an asset with its camera settings as the recipe, exported to
 * Pictures/Studio. Used by the Camera room and by the Loop projector for incoming captures.
 */
fun saveJpeg(
    context: Context,
    store: ProjectStore,
    developed: Developed,
    info: CaptureInfo,
    operation: String,
    device: String,
    parents: List<String>,
): AssetRecord {
    val tmp = File(store.tempDir, "capture-${System.nanoTime()}.jpg").apply { writeBytes(developed.jpeg) }
    val record = store.addAssetFile(tmp, "jpg", parents, operation, device, developed.widthPx, developed.heightPx, Recipe(capture = info))
    exportToMedia(context, "studio-${record.id}.jpg", "image/jpeg") { out ->
        store.fileOf(record).inputStream().use { it.copyTo(out) }
    }?.let { store.update(record.copy(exportedUri = it.toString())) }
    return record
}

/** Develops and files a Camera room capture, plus its DNG as a child when RAW was on. */
fun saveCapture(
    context: Context,
    store: ProjectStore,
    spec: CameraSpec,
    shots: List<Shot>,
    info: CaptureInfo,
    half: Boolean,
    overlay: OverlayRender? = null,
): AssetRecord {
    // A burnt-in Library image is a parent of the capture, so the lineage shows where it came from.
    val parents = listOfNotNull(overlay?.takeIf { it.burn }?.assetId)
    val record = saveJpeg(context, store, develop(spec, shots, info.stack, half, overlay), info, "camera.capture", Build.MODEL, parents)
    shots.firstNotNullOfOrNull { it.dng }?.let { dng ->
        val rawTmp = File(store.tempDir, "capture.dng").apply { writeBytes(dng) }
        val r = store.addAssetFile(rawTmp, "dng", listOf(record.id), "camera.raw", Build.MODEL, spec.rawSize?.width ?: 0, spec.rawSize?.height ?: 0, recipe = null)
        exportToMedia(context, "studio-${r.id}.dng", "image/x-adobe-dng") { out ->
            store.fileOf(r).inputStream().use { it.copyTo(out) }
        }?.let { store.update(r.copy(exportedUri = it.toString())) }
    }
    return record
}
