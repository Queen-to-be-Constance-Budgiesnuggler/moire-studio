package dk.cbavf.studio.bridge

import dk.cbavf.studio.bridge.model.WatchFolder

/**
 * Pure logic for filesystem watching in the Bridge room. No Android types here,
 * so this is unit-testable on the JVM.
 *
 * [watchPaths] returns the subdirectories of external storage to observe, and
 * [isImageEvent] decides whether a FileObserver callback should trigger a rescan.
 */
object BridgeWatcher {

    /** Image extensions the watcher accepts (lowercase, no dot). */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    /**
     * Subdirectory paths (relative to external storage root) for each [WatchFolder].
     * [WatchFolder.Other] has no fixed path and is not watched.
     */
    fun watchPaths(): List<Pair<WatchFolder, String>> = listOf(
        WatchFolder.Camera to "DCIM/Camera",
        WatchFolder.Screenshots to "Pictures/Screenshots",
        WatchFolder.Screenshots to "DCIM/Screenshots",
        WatchFolder.Fv5 to "Pictures/FV5",
        WatchFolder.Pictures to "Pictures",
    )

    /** True when [fileName] has an image extension we care about. */
    fun isImageFile(fileName: String?): Boolean {
        if (fileName == null) return false
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.lastIndex) return false
        return fileName.substring(dot + 1).lowercase() in IMAGE_EXTENSIONS
    }

    /**
     * True when a FileObserver event mask includes CLOSE_WRITE or MOVED_TO and the
     * file name is an image we accept. These are the two events that mean a complete
     * file is ready to read.
     */
    fun isImageEvent(eventMask: Int, fileName: String?): Boolean {
        // FileObserver constants: CLOSE_WRITE = 0x08, MOVED_TO = 0x80
        val dominated = eventMask and (0x08 or 0x80)
        return dominated != 0 && isImageFile(fileName)
    }
}
