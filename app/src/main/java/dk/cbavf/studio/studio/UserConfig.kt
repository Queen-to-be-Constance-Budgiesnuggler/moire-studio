package dk.cbavf.studio.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Who you are and every studio flag: the contents of `user.profile`. New fields get a default, so a file written by an
 * older build still loads.
 */
@Serializable
data class UserProfile(
    val version: Int = 1,
    val name: String = "",
    val handle: String = "",
    val about: String = "",
    val theme: String = "",
    val uiScale: Float = 1f,
    val startRoom: String = "",
    val keepScreenOn: Boolean = false,
    val haptics: Boolean = true,
    val loopRole: String = "",
    val loopHost: String = "",
    val debugLevel: Int = 0,
    val terminalVisible: Boolean = false,
    val copyAsScript: Boolean = false,
    val abTwoFingerHold: Boolean = false,
    val paletteNotification: Boolean = false,
    /** Package names of the apps the Bridge offers to open (chosen from the apps on this phone). */
    val favouriteApps: List<String> = emptyList(),
    val mathParams: Map<String, Double> = emptyMap(),
    val enabledPlugins: List<String> = emptyList(),
    /** Feedback to the developer: "off" (default), "basic" or "full". See [dk.cbavf.studio.feedback.FeedbackLevel]. */
    val feedbackLevel: String = "off",
    /** True once the person has answered the first-run question, so it is asked only once. */
    val feedbackAsked: Boolean = false,
    /** A random id made on this phone the first time feedback is switched on; it names no one. Reset or deleted from Config. */
    val installId: String = "",
    val feedbackWifiOnly: Boolean = true,
    /** OSC out: the computer the OSC out unit sends to (an IP address or a name), and the port. */
    val oscOutHost: String = "",
    val oscOutPort: Int = 9001,
    /** Where reports go; empty = the address built into this build. */
    val feedbackUrl: String = "",
)

/** What is true of this phone right now: the contents of `user.workspace`. */
@Serializable
data class UserWorkspace(
    val version: Int = 1,
    val project: String = "default",
    val lastRoom: String = "",
    /** Width of the controls panel beside the picture in landscape, in dp; 0 = the default. */
    val panelWidthDp: Float = 0f,
    /** The exit (its time) whose crash banner the person already dismissed. */
    val recoveryDismissedAt: Long = 0L,
    /** Envelopes attached to controls, keyed `room.label`. */
    val modulations: Map<String, dk.cbavf.studio.modulation.ControlModulation> = emptyMap(),
)

object UserConfigJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(p: UserProfile): String = json.encodeToString(UserProfile.serializer(), p)

    fun encode(w: UserWorkspace): String = json.encodeToString(UserWorkspace.serializer(), w)

    /** The profile in [text]; missing, blank or damaged text gives the defaults (never an exception). */
    fun decodeProfile(text: String?): UserProfile {
        if (text.isNullOrBlank()) return UserProfile()
        return clean(runCatching { json.decodeFromString(UserProfile.serializer(), text) }.getOrDefault(UserProfile()))
    }

    fun decodeWorkspace(text: String?): UserWorkspace {
        if (text.isNullOrBlank()) return UserWorkspace()
        return runCatching { json.decodeFromString(UserWorkspace.serializer(), text) }.getOrDefault(UserWorkspace())
    }

    /** Keeps values in their valid ranges: interface size 0.8 to 1.4, debug level 0 to 3. */
    fun clean(p: UserProfile): UserProfile = p.copy(
        uiScale = p.uiScale.coerceIn(0.8f, 1.4f), debugLevel = p.debugLevel.coerceIn(0, 3), oscOutPort = p.oscOutPort.coerceIn(1, 65535),
    )

    /** Moves the old SharedPreferences values ("studio" and "loop", read as plain maps) into the two new files' shapes. */
    fun migrate(prefs: Map<String, Any?>, loop: Map<String, Any?>): Pair<UserProfile, UserWorkspace> {
        val defaults = UserProfile()
        fun str(m: Map<String, Any?>, key: String): String? = m[key] as? String
        fun bool(m: Map<String, Any?>, key: String): Boolean? = m[key] as? Boolean
        val profile = clean(
            defaults.copy(
                theme = str(prefs, "theme") ?: defaults.theme,
                uiScale = (prefs["uiScale"] as? Number)?.toFloat() ?: defaults.uiScale,
                startRoom = str(prefs, "startRoom") ?: defaults.startRoom,
                keepScreenOn = bool(prefs, "keepScreenOn") ?: defaults.keepScreenOn,
                haptics = bool(prefs, "haptics") ?: defaults.haptics,
                name = str(prefs, "profileName") ?: defaults.name,
                handle = str(prefs, "profileHandle") ?: defaults.handle,
                about = str(prefs, "profileAbout") ?: defaults.about,
                loopRole = str(loop, "role") ?: defaults.loopRole,
                loopHost = str(loop, "host") ?: defaults.loopHost,
            )
        )
        val workspace = UserWorkspace(
            project = str(prefs, "project") ?: UserWorkspace().project,
            lastRoom = str(prefs, "room") ?: UserWorkspace().lastRoom,
        )
        return profile to workspace
    }
}
