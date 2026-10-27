package dk.cbavf.studio.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.BuildConfig
import dk.cbavf.studio.debug.Debug
import dk.cbavf.studio.math.MathParams
import dk.cbavf.studio.studio.CrashRecovery
import dk.cbavf.studio.studio.UserConfigStore
import dk.cbavf.studio.studio.UserProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * Feedback to the developer, only when the person has switched it on in Studio → Config.
 *
 * The studio records which rooms and controls are used, errors and crashes into a small report, keeps reports in a bounded
 * queue and sends them to the feedback server. [FeedbackLevel] decides what is in a report ([ReportBuilder]); at Off nothing is
 * recorded, queued or sent.
 */
object Feedback {
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "feedback").apply { isDaemon = true } }
    private var attached by mutableStateOf(false)
    private lateinit var context: Context
    private lateinit var config: UserConfigStore
    private lateinit var queue: FeedbackQueue
    private var recorder = SessionRecorder(UUID.randomUUID().toString().take(8), System::currentTimeMillis)
    private var lastRoom: String? = null
    private var catalog = UseCaseCatalog.EMPTY

    /** What the last send did, for the Config screen. */
    var status by mutableStateOf("")
        private set
    var pending by mutableIntStateOf(0)
        private set

    private val profile: UserProfile get() = config.profile

    /** Off when this build has no feedback , whatever the profile says. */
    val level: FeedbackLevel
        get() = if (!BuildConfig.FEEDBACK_ENABLED || !attached) FeedbackLevel.Off else FeedbackLevel.of(profile.feedbackLevel)

    val availableInBuild: Boolean get() = BuildConfig.FEEDBACK_ENABLED

    val serverUrl: String get() = (if (attached) profile.feedbackUrl else "").ifBlank { BuildConfig.FEEDBACK_URL }.trimEnd('/')

    /** True until the person has answered the first-run question (and only in builds that have feedback). */
    val shouldAsk: Boolean get() = BuildConfig.FEEDBACK_ENABLED && attached && !profile.feedbackAsked

    fun attach(context: Context, config: UserConfigStore) {
        if (attached) return
        this.context = context.applicationContext
        this.config = config
        queue = FeedbackQueue(File(this.context.filesDir, "feedback/outbox"))
        catalog = UseCaseCatalog.parse(runCatching { this.context.assets.open("usecases.json").bufferedReader().readText() }.getOrNull())
        attached = true
        pending = queue.pending().size
        installCrashHandler()
        if (level != FeedbackLevel.Off) {
            reportLastExit()
            flush()
        }
    }

    // ---- what the person chooses ----

    fun setLevel(l: FeedbackLevel) {
        config.updateProfile { p ->
            p.copy(feedbackLevel = l.wire, feedbackAsked = true, installId = if (l != FeedbackLevel.Off && p.installId.isBlank()) UUID.randomUUID().toString() else p.installId)
        }
        if (l == FeedbackLevel.Off) { queue.clear(); pending = 0 } else flush()
    }

    fun setWifiOnly(v: Boolean) = config.updateProfile { it.copy(feedbackWifiOnly = v) }

    fun setServer(url: String) = config.updateProfile { it.copy(feedbackUrl = url.trim()) }

    /** The first-run question was answered with "not now": ask no more, send nothing. */
    fun declineForNow() = config.updateProfile { it.copy(feedbackAsked = true) }

    /** Throws away the id; the next report from this phone is a new, unrelated install. */
    fun resetId() = config.updateProfile { it.copy(installId = if (it.feedbackLevel == "off") "" else UUID.randomUUID().toString()) }

    /** Asks the server to delete everything held under this phone's id, empties the queue, and forgets the id. */
    fun deleteMyData() {
        val id = profile.installId
        queue.clear(); pending = 0
        config.updateProfile { it.copy(installId = "") }
        if (id.isBlank() || serverUrl.isBlank()) { status = "Nothing held for this phone."; return }
        net.execute {
            status = when (val code = post("/v1/forget", """{"install":"$id"}""")) {
                200 -> "Deleted everything the server held for this phone."
                else -> "Could not reach the server (code $code). Try again later; the local queue is already empty."
            }
        }
    }

    // ---- what the studio tells it ----

    fun enterRoom(name: String) {
        if (level == FeedbackLevel.Off) return
        lastRoom = name
        recorder.enterRoom(name)
    }

    fun control(id: String) {
        if (level == FeedbackLevel.Off) return
        recorder.control(id)
    }

    /** A tap on a control with this text in [room]; kept only if the use-case catalog names it (see [UseCaseCatalog]). */
    fun tap(room: String, label: String) {
        if (level == FeedbackLevel.Off) return
        catalog.keyFor(room, label)?.let { recorder.tap(it) }
    }

    /** Called by [Debug] for every error or warning, whatever the debug level is. */
    fun error(tag: String, msg: String) {
        if (level == FeedbackLevel.Off) return
        recorder.error(tag, msg)
    }

    /** The studio went to the background: report what happened since the last report, then try to send. */
    fun onStop() {
        if (level == FeedbackLevel.Off) return
        recorder.pause()
        checkpoint()
    }

    fun onStart() {
        if (level == FeedbackLevel.Off) return
        lastRoom?.let { recorder.enterRoom(it) }
    }

    /** Queues the increment since the last checkpoint and starts a fresh recorder (so reports add up on the server), then sends. */
    fun checkpoint() {
        if (level == FeedbackLevel.Off) return
        val rec = recorder
        if (rec.hasContent()) {
            sessionReport(rec.stats())?.let { enqueue(it) }
            recorder = SessionRecorder(rec.sessionId, System::currentTimeMillis)
            lastRoom?.let { recorder.enterRoom(it) }
        }
        flush()
    }

    // ---- reports ----

    private fun appInfo() = AppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR)
    private fun deviceInfo() = DeviceInfo(Build.MODEL ?: "", Build.VERSION.SDK_INT)

    private fun settingsMap(): Map<String, String> {
        val p = profile
        val m = linkedMapOf(
            "theme" to p.theme, "uiScale" to p.uiScale.toString(), "startRoom" to p.startRoom, "keepScreenOn" to p.keepScreenOn.toString(),
            "haptics" to p.haptics.toString(), "debugLevel" to p.debugLevel.toString(), "abTwoFingerHold" to p.abTwoFingerHold.toString(),
            "paletteNotification" to p.paletteNotification.toString(), "plugins" to p.enabledPlugins.size.toString(),
        )
        MathParams.snapshot().forEach { (k, v) -> m["math.$k"] = v.toString() }
        return m
    }

    private fun sessionReport(stats: SessionRecorder.Stats): FeedbackReport? =
        ReportBuilder.session(level, profile.installId, appInfo(), deviceInfo(), stats, Debug.log.lines(), settingsMap())

    /** What the next report would contain, laid out to read ("See what would be sent"). */
    fun preview(): String {
        if (level == FeedbackLevel.Off) return "Feedback is off. Nothing is recorded or sent."
        val r = sessionReport(recorder.stats()) ?: return "Nothing to report yet."
        return FeedbackJson.pretty(r) + "\n\nWaiting to be sent: $pending report(s)."
    }

    /** A note the person typed, sent now (queued if offline). */
    fun sendNote(text: String): Boolean {
        if (!attached || !availableInBuild) return false
        val id = profile.installId.ifBlank { UUID.randomUUID().toString().also { n -> config.updateProfile { it.copy(installId = n) } } }
        val r = ReportBuilder.note(id, appInfo(), deviceInfo(), recorder.sessionId, System.currentTimeMillis(), text) ?: return false
        enqueue(r)
        flush(force = true)
        return true
    }

    private fun enqueue(r: FeedbackReport) {
        queue.add(FeedbackJson.encode(r))
        pending = queue.pending().size
    }

    // ---- crashes ----

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                if (level != FeedbackLevel.Off) {
                    val frames = e.stackTrace.map { it.toString() }
                    ReportBuilder.crash(level, profile.installId, appInfo(), deviceInfo(), recorder.sessionId, System.currentTimeMillis(),
                        "${e.javaClass.name}: ${e.message.orEmpty()}", frames, "crash", Debug.log.lines())?.let { queue.add(FeedbackJson.encode(it)) }
                    sessionReport(recorder.stats())?.let { queue.add(FeedbackJson.encode(it)) }
                }
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** A stop the phone recorded that is not an app exception (not responding, killed for memory): reported once. */
    private fun reportLastExit() {
        val (reason, at) = CrashRecovery.lastExit(context) ?: return
        if (reason == android.app.ApplicationExitInfo.REASON_CRASH) return // the exception handler already reported this one
        val what = CrashRecovery.describe(reason) ?: return
        val marker = File(context.filesDir, "feedback/last-exit")
        if (runCatching { marker.readText().trim().toLong() }.getOrDefault(0L) >= at) return
        marker.parentFile?.mkdirs()
        marker.writeText(at.toString())
        ReportBuilder.crash(level, profile.installId, appInfo(), deviceInfo(), "", at, "App exit: $what", emptyList(), "exit-$reason", emptyList())
            ?.let { enqueue(it) }
    }

    // ---- sending ----

    private fun unmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Sends what is waiting, oldest first, on a background thread. Stops at the first failure and tries again at the next flush. */
    fun flush(force: Boolean = false) {
        if (!attached || serverUrl.isBlank() || queue.pending().isEmpty()) return
        if (!force && profile.feedbackWifiOnly && !unmetered()) return
        net.execute {
            var sent = 0
            for (f in queue.pending()) {
                val code = post("/v1/report", f.readText())
                when {
                    code == 200 -> { queue.remove(f); sent++ }
                    code in 400..499 && code != 429 -> queue.remove(f) // refused for good (bad shape, blocked); retrying cannot help
                    else -> { status = "Could not send (code $code); will try again."; break }
                }
            }
            pending = queue.pending().size
            if (sent > 0) status = "Sent $sent report(s) at ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())}."
        }
    }

    /** POSTs [json] (gzip) to the server and returns the HTTP code, or -1 when it could not connect. */
    private fun post(path: String, json: String): Int = runCatching {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
        val c = URL(serverUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Content-Encoding", "gzip")
        c.setRequestProperty("X-Key", BuildConfig.FEEDBACK_KEY)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        c.disconnect()
        Debug.t("feedback", "POST $path -> $code")
        code
    }.getOrElse { e ->
        // Trace level on purpose: an error line here would itself become a feedback error.
        Debug.t("feedback", "POST $path failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
        status = "Could not reach the server (${e.javaClass.simpleName}). Reports wait on this phone and are retried."
        -1
    }
}
