package dk.cbavf.studio.studio

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/** What the phone says happened to the studio's last run, for the banner shown after a crash. */
object CrashRecovery {
    /** A sentence fragment for an exit that was not the person's doing, or null for an ordinary exit. */
    fun describe(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed"
        ApplicationExitInfo.REASON_ANR -> "stopped responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "was closed because the phone ran short of memory"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        else -> null
    }

    /** The banner text for an exit at [exitAtMs], or null when there is nothing to say (an ordinary exit, or one already dismissed). */
    fun banner(reason: Int, exitAtMs: Long, dismissedAtMs: Long): String? {
        if (exitAtMs <= dismissedAtMs) return null
        val what = describe(reason) ?: return null
        return "Last time the studio $what. Everything is autosaved as you work, so your drawing, edits and projects should be as you left them. Check the History lists if something looks off."
    }

    /** The most recent exit of this app: its reason and when it happened, or null if the phone keeps none. */
    fun lastExit(context: Context): Pair<Int, Long>? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1) }.getOrNull()?.firstOrNull() ?: return null
        return info.reason to info.timestamp
    }
}
