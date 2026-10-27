package dk.cbavf.studio.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How much the person lets the studio report. Off is the default and sends nothing on its own. */
enum class FeedbackLevel(val wire: String, val title: String, val blurb: String) {
    Off("off", "Off", "Nothing is recorded or sent."),
    Basic(
        "basic", "Basic",
        "Which rooms you use and for how long, how often each control is used, errors and crashes (a short stack trace), the app version and the phone model.",
    ),
    Full(
        "full", "Full",
        "Everything in Basic, plus the last 300 debug lines and your studio settings (flags and maths parameters), to find a bug.",
    );

    companion object {
        fun of(wire: String?): FeedbackLevel = entries.firstOrNull { it.wire == wire } ?: Off
    }
}

@Serializable data class AppInfo(val version: String = "", val build: Int = 0, val flavor: String = "")
@Serializable data class DeviceInfo(val model: String = "", val sdk: Int = 0)
@Serializable data class RoomUse(val ms: Long = 0, val visits: Int = 0)
@Serializable data class ErrorEntry(val t: Long = 0, val tag: String = "", val msg: String = "")
@Serializable data class CrashInfo(val exception: String = "", val frames: List<String> = emptyList(), val reason: String = "")

/** One report as the feedback server takes it (`POST /v1/report`). Field names are the wire format. */
@Serializable
data class FeedbackReport(
    val schema: Int = 1,
    val kind: String,
    val level: String,
    val install: String,
    val session: String = "",
    val app: AppInfo = AppInfo(),
    val device: DeviceInfo = DeviceInfo(),
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val durationMs: Long = 0,
    val rooms: Map<String, RoomUse> = emptyMap(),
    val controls: Map<String, Int> = emptyMap(),
    /** Taps on controls named in the studio's use-case catalog, keyed "Room|Label". Only catalog labels are ever kept, so nothing the person typed or named can appear. */
    val taps: Map<String, Int> = emptyMap(),
    val errors: List<ErrorEntry> = emptyList(),
    val crash: CrashInfo? = null,
    val note: String? = null,
    val log: List<String>? = null,
    val settings: Map<String, String>? = null,
)

object FeedbackJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val pretty = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }

    fun encode(r: FeedbackReport): String = json.encodeToString(FeedbackReport.serializer(), r)

    /** The same report laid out for a person to read ("See what would be sent"). */
    fun pretty(r: FeedbackReport): String = pretty.encodeToString(FeedbackReport.serializer(), r)

    fun decode(text: String): FeedbackReport? = runCatching { json.decodeFromString(FeedbackReport.serializer(), text) }.getOrNull()
}

/** Takes things a person would not want to send out of free text: addresses, links, file paths, network addresses. */
object Redactor {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val url = Regex("(?i)\\b(?:https?|content|file)://\\S+")
    private val unixPath = Regex("(?<![\\w.])/(?:storage|sdcard|data|mnt|home|Users|emulated)\\S*")
    private val winPath = Regex("\\b[A-Za-z]:\\\\\\S*")
    private val ip = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    fun scrub(text: String, max: Int = 300): String {
        var s = text
        s = url.replace(s, "<link>")
        s = email.replace(s, "<email>")
        s = unixPath.replace(s, "<path>")
        s = winPath.replace(s, "<path>")
        s = ip.replace(s, "<ip>")
        return if (s.length > max) s.take(max) + "…" else s
    }
}

/** What one run of the studio has been used for. Pure bookkeeping; the clock is passed in so it can be tested. */
class SessionRecorder(val sessionId: String, private val clock: () -> Long, val startedAt: Long = clock()) {
    private val rooms = LinkedHashMap<String, LongArray>()      // name -> [ms, visits]
    private val controls = LinkedHashMap<String, Int>()
    private val taps = LinkedHashMap<String, Int>()
    private val lastTap = HashMap<String, Long>()
    private val lastTouch = HashMap<String, Long>()
    private val errors = ArrayList<ErrorEntry>()
    private var current: String? = null
    private var since = 0L
    private var foregroundMs = 0L
    private var foregroundSince = -1L

    /** The person moved to [name]. Also counts as being in the foreground. */
    @Synchronized fun enterRoom(name: String) {
        closeRoom()
        current = name
        since = clock()
        rooms.getOrPut(name) { LongArray(2) }[1]++
        if (foregroundSince < 0) foregroundSince = since
    }

    /** The studio went to the background: time stops counting. */
    @Synchronized fun pause() {
        closeRoom()
        if (foregroundSince >= 0) { foregroundMs += clock() - foregroundSince; foregroundSince = -1 }
    }

    /** The studio came back; counting resumes in the room it was in. */
    @Synchronized fun resume() {
        val room = current ?: return
        if (foregroundSince < 0) foregroundSince = clock()
        since = clock()
        current = room
    }

    private fun closeRoom() {
        val room = current ?: return
        rooms.getOrPut(room) { LongArray(2) }[0] += (clock() - since).coerceAtLeast(0)
        since = clock()
    }

    /** A control was used. A drag counts once, not once per movement: repeat touches within 1.5 s are one use. */
    @Synchronized fun control(id: String) {
        val now = clock()
        val last = lastTouch[id]
        lastTouch[id] = now
        if (last != null && now - last < CONTROL_GAP_MS) return
        if (controls.size >= MAX_CONTROLS && id !in controls) return
        controls[id] = (controls[id] ?: 0) + 1
    }

    /** A tap on a catalog control ([key] is "Room|Label"). Two taps within 0.6 s on the same control count once. */
    @Synchronized fun tap(key: String) {
        val now = clock()
        val last = lastTap[key]
        lastTap[key] = now
        if (last != null && now - last < TAP_GAP_MS) return
        if (taps.size >= MAX_TAPS && key !in taps) return
        taps[key] = (taps[key] ?: 0) + 1
    }

    @Synchronized fun error(tag: String, msg: String) {
        val e = ErrorEntry(clock(), Redactor.scrub(tag, 40), Redactor.scrub(msg))
        val last = errors.lastOrNull()
        if (last != null && last.tag == e.tag && last.msg == e.msg) return
        if (errors.size >= MAX_ERRORS) errors.removeAt(0)
        errors.add(e)
    }

    /** True when there is something worth sending. */
    @Synchronized fun hasContent(): Boolean = rooms.isNotEmpty() || errors.isNotEmpty() || taps.isNotEmpty()

    /** The numbers so far, as of [now]. Does not stop the recorder. */
    @Synchronized fun stats(now: Long = clock()): Stats {
        val live = current?.let { it to (now - since).coerceAtLeast(0) }
        val roomUse = rooms.mapValues { (name, v) -> RoomUse(v[0] + if (live?.first == name && foregroundSince >= 0) live.second else 0, v[1].toInt()) }
        val fg = foregroundMs + if (foregroundSince >= 0) now - foregroundSince else 0
        return Stats(sessionId, startedAt, now, fg, roomUse, controls.toMap(), errors.toList(), taps.toMap())
    }

    data class Stats(
        val session: String, val startedAt: Long, val endedAt: Long, val durationMs: Long,
        val rooms: Map<String, RoomUse>, val controls: Map<String, Int>, val errors: List<ErrorEntry>,
        val taps: Map<String, Int> = emptyMap(),
    )

    companion object {
        const val CONTROL_GAP_MS = 1500L
        const val MAX_CONTROLS = 200
        const val MAX_ERRORS = 50
        const val MAX_TAPS = 400
        const val TAP_GAP_MS = 600L
    }
}

/** Puts a report together and applies the level: this is the one place that decides what leaves the phone. */
object ReportBuilder {
    fun session(
        level: FeedbackLevel, install: String, app: AppInfo, device: DeviceInfo, stats: SessionRecorder.Stats,
        log: List<String>, settings: Map<String, String>,
    ): FeedbackReport? {
        if (level == FeedbackLevel.Off) return null
        val full = level == FeedbackLevel.Full
        return FeedbackReport(
            kind = "session", level = level.wire, install = install, session = stats.session, app = app, device = device,
            startedAt = stats.startedAt, endedAt = stats.endedAt, durationMs = stats.durationMs,
            rooms = stats.rooms, controls = stats.controls, taps = stats.taps, errors = stats.errors,
            log = if (full) log.takeLast(300).map { Redactor.scrub(it) } else null,
            settings = if (full) settings else null,
        )
    }

    fun crash(
        level: FeedbackLevel, install: String, app: AppInfo, device: DeviceInfo, session: String, at: Long,
        exception: String, frames: List<String>, reason: String, log: List<String>,
    ): FeedbackReport? {
        if (level == FeedbackLevel.Off) return null
        return FeedbackReport(
            kind = "crash", level = level.wire, install = install, session = session, app = app, device = device,
            startedAt = at, endedAt = at,
            crash = CrashInfo(Redactor.scrub(exception), frames.take(14).map { Redactor.scrub(it, 160) }, reason),
            log = if (level == FeedbackLevel.Full) log.takeLast(300).map { Redactor.scrub(it) } else null,
        )
    }

    /** A note the person wrote and chose to send. It goes whatever the level is, because they pressed Send; it carries no usage data. */
    fun note(install: String, app: AppInfo, device: DeviceInfo, session: String, at: Long, text: String): FeedbackReport? {
        val body = text.trim()
        if (body.isEmpty()) return null
        return FeedbackReport(
            kind = "note", level = FeedbackLevel.Basic.wire, install = install, session = session, app = app, device = device,
            startedAt = at, endedAt = at, note = body.take(4000),
        )
    }
}
