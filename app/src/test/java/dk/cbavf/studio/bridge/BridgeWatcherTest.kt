package dk.cbavf.studio.bridge

import dk.cbavf.studio.bridge.model.WatchFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWatcherTest {

    // FileObserver constants (same values as android.os.FileObserver)
    private val CLOSE_WRITE = 0x08
    private val MOVED_TO = 0x80
    private val CREATE = 0x100
    private val ACCESS = 0x01

    @Test
    fun acceptsStandardImageExtensions() {
        assertTrue(BridgeWatcher.isImageFile("photo.jpg"))
        assertTrue(BridgeWatcher.isImageFile("photo.jpeg"))
        assertTrue(BridgeWatcher.isImageFile("photo.png"))
        assertTrue(BridgeWatcher.isImageFile("photo.webp"))
        assertTrue(BridgeWatcher.isImageFile("photo.heic"))
        assertTrue(BridgeWatcher.isImageFile("photo.heif"))
    }

    @Test
    fun rejectsNonImageExtensions() {
        assertFalse(BridgeWatcher.isImageFile("video.mp4"))
        assertFalse(BridgeWatcher.isImageFile("document.pdf"))
        assertFalse(BridgeWatcher.isImageFile("archive.zip"))
        assertFalse(BridgeWatcher.isImageFile("noext"))
        assertFalse(BridgeWatcher.isImageFile("trailingdot."))
    }

    @Test
    fun rejectsNullFileName() {
        assertFalse(BridgeWatcher.isImageFile(null))
    }

    @Test
    fun caseInsensitiveExtension() {
        assertTrue(BridgeWatcher.isImageFile("PHOTO.JPG"))
        assertTrue(BridgeWatcher.isImageFile("photo.Png"))
        assertTrue(BridgeWatcher.isImageFile("photo.HEIC"))
    }

    @Test
    fun closeWriteOnImageIsAccepted() {
        assertTrue(BridgeWatcher.isImageEvent(CLOSE_WRITE, "photo.jpg"))
    }

    @Test
    fun movedToOnImageIsAccepted() {
        assertTrue(BridgeWatcher.isImageEvent(MOVED_TO, "photo.png"))
    }

    @Test
    fun createAloneIsRejected() {
        assertFalse(BridgeWatcher.isImageEvent(CREATE, "photo.jpg"))
    }

    @Test
    fun accessAloneIsRejected() {
        assertFalse(BridgeWatcher.isImageEvent(ACCESS, "photo.jpg"))
    }

    @Test
    fun imageEventWithNonImageFileIsRejected() {
        assertFalse(BridgeWatcher.isImageEvent(CLOSE_WRITE, "video.mp4"))
    }

    @Test
    fun imageEventWithNullFileNameIsRejected() {
        assertFalse(BridgeWatcher.isImageEvent(CLOSE_WRITE, null))
    }

    @Test
    fun watchPathsIncludeAllExpectedFolders() {
        val paths = BridgeWatcher.watchPaths()
        val folders = paths.map { it.first }.toSet()
        assertTrue(WatchFolder.Camera in folders)
        assertTrue(WatchFolder.Screenshots in folders)
        assertTrue(WatchFolder.Fv5 in folders)
        assertTrue(WatchFolder.Pictures in folders)
        // Other has no fixed path and is not watched.
        assertFalse(WatchFolder.Other in folders)
    }

    @Test
    fun watchPathsContainExpectedSubdirectories() {
        val paths = BridgeWatcher.watchPaths().map { it.second }
        assertTrue("DCIM/Camera" in paths)
        assertTrue("Pictures/FV5" in paths)
        assertTrue("Pictures" in paths)
    }

    @Test
    fun screenshotsWatchedInBothLocations() {
        val screenshotPaths = BridgeWatcher.watchPaths()
            .filter { it.first == WatchFolder.Screenshots }
            .map { it.second }
        assertEquals(2, screenshotPaths.size)
        assertTrue("Pictures/Screenshots" in screenshotPaths)
        assertTrue("DCIM/Screenshots" in screenshotPaths)
    }
}
