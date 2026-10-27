package dk.cbavf.studio.camera

import dk.cbavf.studio.debug.Debug
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import dk.cbavf.studio.camera.model.WhiteBalance
import dk.cbavf.studio.camera.model.awaitOpened
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** What one camera can do, read from its characteristics. */
data class CameraSpec(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val jpegSize: Size,
    val rawSize: Size?,
    val previewSize: Size,
    val manualSensor: Boolean,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
    /** Diopters; 0 = fixed focus. */
    val minFocusDiopters: Float,
    val apertures: List<Float>,
    val hasFlash: Boolean,
    /** Zoom ratios the camera accepts (below 1 = a wider lens on a multi-camera module). */
    val zoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
) {
    val label: String
        get() = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front $id"
            CameraCharacteristics.LENS_FACING_BACK -> "Back $id"
            else -> "Camera $id"
        }
}

/** Settings applied to the preview and to every capture. Null fields mean "auto". */
data class CameraSettings(
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val focusDiopters: Float? = null,
    val aperture: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.Auto,
    val torch: Boolean = false,
    val zoom: Float = 1f,
)

/** One captured frame: JPEG bytes (not rotated) and, if asked for, a DNG. */
class Shot(val jpeg: ByteArray, val dng: ByteArray?)

/**
 * Camera2 wrapper for the Camera room: one open camera, a preview surface, a JPEG reader and
 * (where supported) a RAW reader. All camera callbacks run on a private thread.
 */
class CameraController(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    var spec: CameraSpec? = null
        private set
    private var settings = CameraSettings()

    /** One [open] at a time; [generation] goes up on every [close], so an open in flight knows it is stale. */
    private val opening = Mutex()
    private val generation = AtomicInteger()

    /** Every camera's spec; asks the camera service once per camera, so it runs off the main thread. */
    suspend fun cameras(): List<CameraSpec> = withContext(Dispatchers.IO) {
        manager.cameraIdList.mapNotNull { id -> runCatching { specOf(id) }.getOrNull() }
    }

    private fun specOf(id: String): CameraSpec {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val jpeg = map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
        val raw = if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        } else {
            null
        }
        // Largest preview no bigger than 1920×1440 with the JPEG's aspect, so the overlay lines up.
        val aspect = jpeg.width.toFloat() / jpeg.height
        val previews = map.getOutputSizes(android.view.SurfaceHolder::class.java)
        val preview = previews
            .filter { it.width <= 1920 && it.height <= 1440 && kotlin.math.abs(it.width.toFloat() / it.height - aspect) < 0.02f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: previews.minBy { kotlin.math.abs(it.width.toFloat() / it.height - aspect) }
        val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        return CameraSpec(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: -1,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            jpegSize = jpeg,
            rawSize = raw,
            previewSize = preview,
            manualSensor = manual,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }.takeIf { manual },
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }.takeIf { manual },
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper }
                ?: 1f..(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f),
        )
    }

    /**
     * Opens [cameraId] and starts the preview on [surface]. Needs the CAMERA permission. One open
     * at a time; a [close] (or a newer open) while this one waits makes it stale, and a stale or
     * cancelled open closes whatever it had opened instead of keeping it.
     */
    @SuppressLint("MissingPermission")
    suspend fun open(cameraId: String, surface: Surface) = opening.withLock {
        close()
        val gen = generation.get()
        // The camera thread closes the old camera first; wait for that before opening this one.
        suspendCoroutine { cont -> if (!handler.post { cont.resume(Unit) }) cont.resume(Unit) }
        val s = withContext(Dispatchers.IO) { specOf(cameraId) }
        if (gen != generation.get()) return@withLock
        val jr = ImageReader.newInstance(s.jpegSize.width, s.jpegSize.height, ImageFormat.JPEG, MAX_BURST + 1)
        val rr = s.rawSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 2) }
        var d: CameraDevice? = null
        var sess: CameraCaptureSession? = null
        fun releaseAll() = closeOnCameraThread(sess, d, jr, rr)
        try {
            val opened = awaitOpened<CameraDevice>({ it.close() }) { deliver, fail ->
                manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) = deliver(camera)
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        fail(IllegalStateException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        fail(IllegalStateException("Camera error $error"))
                    }
                })
            }
            d = opened
            if (gen != generation.get()) { releaseAll(); return@withLock }
            val outputs = listOfNotNull(surface, jr.surface, rr?.surface).map { OutputConfiguration(it) }
            sess = awaitOpened<CameraCaptureSession>({ it.close() }) { deliver, fail ->
                opened.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) = deliver(session)
                        override fun onConfigureFailed(session: CameraCaptureSession) = fail(IllegalStateException("Camera session failed"))
                    })
                )
            }
        } catch (e: Throwable) {
            releaseAll()
            throw e
        }
        if (gen != generation.get()) { releaseAll(); return@withLock }
        spec = s
        preview = surface
        jpegReader = jr
        rawReader = rr
        device = d
        session = sess
        apply(settings)
    }

    /** Applies settings to the running preview. */
    fun apply(newSettings: CameraSettings) {
        settings = newSettings
        val d = device ?: return
        val sess = session ?: return
        val p = preview ?: return
        val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(p)
            fill(this, newSettings)
        }.build()
        runCatching { sess.setRepeatingRequest(request, null, handler) }.onFailure { Debug.w(TAG, "preview request", it) }
    }

    /**
     * Captures [count] frames back to back with the current settings (one burst request, so
     * the sensor timing stays regular). With [raw], the first frame also comes as DNG.
     */
    suspend fun capture(count: Int, raw: Boolean): List<Shot> {
        val d = device ?: error("Camera not open")
        val sess = session ?: error("Camera not open")
        val s = spec ?: error("Camera not open")
        val jr = jpegReader!!
        val rr = rawReader.takeIf { raw }
        val n = count.coerceIn(1, MAX_BURST)

        val jpegs = mutableListOf<ByteArray>()
        val jpegsDone = CompletableDeferred<Unit>()
        jr.setOnImageAvailableListener({ reader ->
            reader.acquireNextImage()?.use { img ->
                val buf = img.planes[0].buffer
                jpegs += ByteArray(buf.remaining()).also { buf.get(it) }
            }
            if (jpegs.size >= n) jpegsDone.complete(Unit)
        }, handler)

        val dngDone = CompletableDeferred<ByteArray?>()
        var rawImage: android.media.Image? = null
        var rawResult: TotalCaptureResult? = null
        fun tryDng() {
            val img = rawImage ?: return
            val res = rawResult ?: return
            val out = ByteArrayOutputStream()
            runCatching {
                DngCreator(manager.getCameraCharacteristics(s.id), res).use { it.writeImage(out, img) }
            }.onFailure { Debug.w(TAG, "DNG", it) }
            img.close()
            dngDone.complete(out.toByteArray().takeIf { it.isNotEmpty() })
        }
        rr?.setOnImageAvailableListener({ reader ->
            if (rawImage == null) rawImage = reader.acquireNextImage() else reader.acquireNextImage()?.close()
            tryDng()
        }, handler)
        if (rr == null) dngDone.complete(null)

        val requests = List(n) { i ->
            d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jr.surface)
                if (i == 0 && rr != null) addTarget(rr.surface)
                fill(this, settings)
                set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }.build()
        }
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (rr != null && rawResult == null) {
                    rawResult = result
                    tryDng()
                }
            }
        }
        sess.captureBurst(requests, callback, handler)
        withTimeout(CAPTURE_TIMEOUT_MS + n * 1000L) {
            jpegsDone.await()
        }
        val dng = withTimeout(CAPTURE_TIMEOUT_MS) { dngDone.await() }
        jr.setOnImageAvailableListener(null, null)
        rr?.setOnImageAvailableListener(null, null)
        apply(settings) // still captures can disturb the preview's AE/AF state
        return jpegs.mapIndexed { i, j -> Shot(j, if (i == 0) dng else null) }
    }

    /** Torch on/off without touching the other settings (Loop strobe). */
    fun torch(on: Boolean) = apply(settings.copy(torch = on))

    /**
     * Lets go of the camera. Closing a camera device can block for a long time, so it is done on
     * the camera thread; the main thread only forgets it.
     */
    fun close() {
        // An open still waiting for the camera sees this and lets go of what it opened.
        generation.incrementAndGet()
        closeOnCameraThread(session, device, jpegReader, rawReader)
        session = null
        device = null
        jpegReader = null
        rawReader = null
    }

    private fun closeOnCameraThread(s: CameraCaptureSession?, d: CameraDevice?, jr: ImageReader?, rr: ImageReader?) {
        if (s == null && d == null && jr == null && rr == null) return
        val work = Runnable {
            runCatching { s?.close() }
            runCatching { d?.close() }
            runCatching { jr?.close() }
            runCatching { rr?.close() }
        }
        // After release the thread is gone: close here rather than not at all.
        if (!handler.post(work)) work.run()
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    private fun fill(b: CaptureRequest.Builder, s: CameraSettings) {
        val manual = s.exposureNs != null && s.iso != null && spec?.manualSensor == true
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, s.exposureNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        if (s.focusDiopters != null && (spec?.minFocusDiopters ?: 0f) > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        s.aperture?.takeIf { it in spec?.apertures.orEmpty() }?.let { b.set(CaptureRequest.LENS_APERTURE, it) }
        b.set(CaptureRequest.CONTROL_AWB_MODE, s.whiteBalance.awbMode)
        spec?.zoomRange?.let { r -> if (r.endInclusive > r.start) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoom.coerceIn(r.start, r.endInclusive)) }
        b.set(CaptureRequest.FLASH_MODE, if (s.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    companion object {
        const val MAX_BURST = 8
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val TAG = "Studio"
    }
}

private val WhiteBalance.awbMode: Int
    get() = when (this) {
        WhiteBalance.Auto -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        WhiteBalance.Daylight -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalance.Cloudy -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalance.Tungsten -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalance.Fluorescent -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        WhiteBalance.Shade -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    }
