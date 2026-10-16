package dk.cbavf.studio.bridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.content.IntentCompat
import dk.cbavf.studio.R
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.exportToMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the screen recorder is doing; observed by the Bridge room. */
sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data class Recording(val sinceMs: Long) : RecordingStatus
    data class Saved(val assetId: String?, val message: String) : RecordingStatus
}

/**
 * Screen recording for the Bridge (e.g. capturing a Samsung app at work, or the projector).
 * Android asks for consent every session; the recording runs in a foreground service as
 * Android 14 requires, and lands in the Library as an "external:screen" asset.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var output: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Foreground first: getMediaProjection fails on Android 14 without it.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        if (data == null || code != Activity.RESULT_OK) return finish(null, "Screen recording was not allowed.")

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: return finish(null, "Screen recording was not allowed.")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            // The user can end it from the status bar too.
            override fun onStop() = stop()
        }, Handler(Looper.getMainLooper()))

        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d.mode
        // Portrait size, even dimensions for the encoder.
        val w = minOf(mode.physicalWidth, mode.physicalHeight) / 2 * 2
        val h = maxOf(mode.physicalWidth, mode.physicalHeight) / 2 * 2
        val fps = d.refreshRate.toInt().coerceAtMost(60)
        val file = File(ProjectStore.open(projectDir(this)).tempDir, "screen.mp4").also { output = it }

        val r = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(w, h)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate((w.toLong() * h * fps / 8).coerceIn(4_000_000, 40_000_000).toInt())
            setOutputFile(file)
        }
        try {
            r.prepare()
        } catch (e: Exception) {
            r.release()
            return finish(null, "Recorder could not start: ${e.message}")
        }
        recorder = r
        display = p.createVirtualDisplay(
            "studio-screen", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
        r.start()
        status.value = RecordingStatus.Recording(System.currentTimeMillis())
    }

    private fun stop() {
        // Also called by the projection's own onStop after we stop it: only the first call acts.
        val r = recorder ?: return
        recorder = null
        val stopped = runCatching { r.stop() }.isSuccess
        r.release()
        display?.release()
        display = null
        projection?.stop()
        projection = null
        val file = output
        if (!stopped || file == null || !file.exists()) {
            file?.delete()
            return finish(null, "The recording was too short to save.")
        }
        // Import on a plain thread: the service is about to go.
        Thread {
            val store = ProjectStore.open(projectDir(this))
            val (w, h) = videoSize(file)
            val record = runCatching {
                store.addAssetFile(file, "mp4", emptyList(), "external:screen", Build.MODEL, w, h, recipe = null)
            }.onFailure { Log.w("Studio", "screen import failed", it) }.getOrNull()
            if (record != null) {
                exportToMedia(this, "studio-${record.id}.mp4", "video/mp4") { out ->
                    store.fileOf(record).inputStream().use { it.copyTo(out) }
                }?.let { store.update(record.copy(exportedUri = it.toString())) }
            }
            finish(record?.id, if (record != null) "Screen recording saved to Library and Movies/Studio." else "Could not save the recording.")
        }.start()
    }

    private fun finish(assetId: String?, message: String) {
        status.value = RecordingStatus.Saved(assetId, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Moiré Studio is recording the screen")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen"
        private const val NOTIFICATION_ID = 1

        private val status = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
        val recording: StateFlow<RecordingStatus> get() = status

        /** Starts recording with the consent result from [MediaProjectionManager.createScreenCaptureIntent]. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenRecordService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }

        fun projectDir(context: Context) = File(context.filesDir, "projects/default")

        private fun videoSize(file: File): Pair<Int, Int> {
            val r = android.media.MediaMetadataRetriever()
            return try {
                r.setDataSource(file.path)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                w to h
            } finally {
                r.release()
            }
        }
    }
}
