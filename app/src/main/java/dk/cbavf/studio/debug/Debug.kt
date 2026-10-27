package dk.cbavf.studio.debug

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The studio's debug output: standard out only, no standard in. Three levels, set in Studio → Config:
 * 1 errors and warnings, 2 info and events, 3 trace. At level 0 nothing is kept (logcat still gets
 * everything, as before). The terminal view ([DebugTerminal]) shows what is kept.
 */
object Debug {
    val log = DebugLog(level = 0, capacity = 2000)

    /** Bumped on every kept line, so a terminal view redraws. */
    var version by mutableIntStateOf(0)
        private set

    var level: Int
        get() = log.level
        set(v) { log.level = v.coerceIn(DebugLog.OFF, DebugLog.TRACE) }

    fun e(tag: String, msg: String, t: Throwable? = null) = emit(DebugLog.ERROR, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = emit(DebugLog.ERROR, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = emit(DebugLog.INFO, tag, msg, t)
    fun t(tag: String, msg: String) = emit(DebugLog.TRACE, tag, msg, null)

    fun clear() {
        log.clear()
        version++
    }

    private fun emit(level: Int, tag: String, msg: String, t: Throwable?) {
        val text = if (t == null) msg else "$msg: ${t.javaClass.simpleName} ${t.message.orEmpty()}"
        // The feedback recorder hears every error, whatever the debug level is (it does nothing unless feedback is on).
        if (level == DebugLog.ERROR) runCatching { dk.cbavf.studio.feedback.Feedback.error(tag, text) }
        if (level in 1..log.level) {
            log.log(level, tag, text)
            version++
        }
        // logcat keeps everything, whatever the level is set to; it is not there in plain unit tests.
        runCatching {
            when (level) {
                DebugLog.ERROR -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
                DebugLog.INFO -> Log.i(tag, msg)
                else -> Log.d(tag, msg)
            }
        }
    }
}
