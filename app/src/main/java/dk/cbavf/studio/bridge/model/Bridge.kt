package dk.cbavf.studio.bridge.model

import kotlinx.serialization.Serializable

/**
 * Apps the studio round-trips through. An entry with no [packages] is a kind of app, chosen by the person through Android's own
 * chooser, so nothing here depends on one phone maker. Entries with [packages] are optional third-party apps, tried in order.
 */
enum class ExternalApp(val label: String, val packages: List<String>) {
    Gallery("Photo editor", emptyList()),
    Notes("Share to an app", emptyList()),
    Fv5("Camera FV-5", listOf("com.flavionet.android.camera.pro", "com.flavionet.android.camera.lite")),
    Other("Other app", emptyList());

    /** Operation name for assets that come back from this app. */
    val operation: String get() = "external:" + name.lowercase()

    companion object {
        fun ofPackage(pkg: String?): ExternalApp? = pkg?.let { p -> entries.firstOrNull { p in it.packages } }
    }
}

/** Something sent out (an asset, or just "FV-5 opened"); returning images are matched against these. */
@Serializable
data class PendingSend(
    val app: ExternalApp,
    /** Library asset that was sent; null for a plain launch (e.g. FV-5 capture session). */
    val assetId: String?,
    val sentAtSec: Long,
)

/** Persistent Bridge state: what has been sent, and how far the watch folders have been read. */
@Serializable
data class BridgeDoc(
    val sends: List<PendingSend> = emptyList(),
    /** Images added after this (MediaStore DATE_ADDED, seconds) show in the inbox; 0 = not set up yet. */
    val watchSinceSec: Long = 0,
    /** MediaStore ids the user dismissed or imported, so they don't come back. */
    val handled: Set<Long> = emptySet(),
)

/** Where a new image appeared, from its MediaStore relative path. */
enum class WatchFolder(val label: String) {
    Camera("Camera"),
    Screenshots("Screenshots"),
    Fv5("FV-5"),
    Pictures("Pictures"),
    Other("Other");

    /** The app most likely to have written here, if any. */
    val likelyApp: ExternalApp?
        get() = when (this) {
            Fv5 -> ExternalApp.Fv5
            else -> null
        }

    companion object {
        fun of(relativePath: String): WatchFolder {
            val p = relativePath.lowercase()
            return when {
                "fv5" in p || "fv-5" in p -> Fv5
                "screenshot" in p -> Screenshots
                p.startsWith("dcim/") -> Camera
                p.startsWith("pictures/") -> Pictures
                else -> Other
            }
        }
    }
}

/** A new image found in a watch folder, with the send it most likely belongs to. */
data class Candidate(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    /** App that wrote the file, from MediaStore (null if unknown). */
    val ownerPackage: String?,
    val addedAtSec: Long,
    val widthPx: Int,
    val heightPx: Int,
    val match: PendingSend?,
    /** True when the match is unambiguous; uncertain matches need a confirm (spec §11). */
    val sure: Boolean,
) {
    val folder: WatchFolder get() = WatchFolder.of(relativePath)

    /** Best guess at the app that made it: the file's owner, else its folder. */
    val app: ExternalApp? get() = ExternalApp.ofPackage(ownerPackage) ?: folder.likelyApp
}

object Matcher {
    /** Images later than this after a send are not matched to it. */
    const val WINDOW_SEC = 30 * 60L

    /**
     * Picks the send an image came back from: sends in the window before it, preferring the app
     * that wrote the image ([app], when known), then the most recent. Sure only if exactly one
     * send fits and it agrees with [app].
     */
    fun match(addedAtSec: Long, app: ExternalApp?, sends: List<PendingSend>): Pair<PendingSend?, Boolean> {
        val inWindow = sends.filter { addedAtSec >= it.sentAtSec && addedAtSec - it.sentAtSec <= WINDOW_SEC }
        if (inWindow.isEmpty()) return null to false
        val byApp = app?.let { a -> inWindow.filter { it.app == a } }.orEmpty()
        val pool = byApp.ifEmpty { inWindow }
        val best = pool.maxBy { it.sentAtSec }
        return best to (pool.size == 1 && best.app == app)
    }

    /** Sends that can no longer match anything new. */
    fun expired(sends: List<PendingSend>, nowSec: Long): List<PendingSend> =
        sends.filter { nowSec - it.sentAtSec > WINDOW_SEC }
}
