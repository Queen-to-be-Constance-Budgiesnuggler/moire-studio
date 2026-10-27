package dk.cbavf.studio.bridge

import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.bridge.model.Matcher
import dk.cbavf.studio.bridge.model.PendingSend
import dk.cbavf.studio.bridge.model.WatchFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    private val gallery = PendingSend(ExternalApp.Gallery, "a1", sentAtSec = 1000)
    private val notes = PendingSend(ExternalApp.Notes, "a2", sentAtSec = 1100)
    private val fv5 = PendingSend(ExternalApp.Fv5, null, sentAtSec = 1200)

    @Test
    fun ownerAppDecidesBetweenSends() {
        val (m, sure) = Matcher.match(1300, ExternalApp.Gallery, listOf(gallery, notes, fv5))
        assertEquals(gallery, m)
        assertTrue(sure)
    }

    @Test
    fun unknownAppFallsBackToNewestSendButIsNotSure() {
        val (m, sure) = Matcher.match(1300, null, listOf(gallery, notes))
        assertEquals(notes, m)
        assertFalse(sure)
    }

    @Test
    fun imagesBeforeASendOrOutsideTheWindowDoNotMatch() {
        assertNull(Matcher.match(999, ExternalApp.Gallery, listOf(gallery)).first)
        assertNull(Matcher.match(1000 + Matcher.WINDOW_SEC + 1, ExternalApp.Gallery, listOf(gallery)).first)
    }

    @Test
    fun twoSendsToTheSameAppAreAmbiguous() {
        val again = gallery.copy(assetId = "a3", sentAtSec = 1150)
        val (m, sure) = Matcher.match(1300, ExternalApp.Gallery, listOf(gallery, again))
        assertEquals(again, m)
        assertFalse(sure)
    }

    @Test
    fun foldersAreClassified() {
        assertEquals(WatchFolder.Fv5, WatchFolder.of("DCIM/FV5/"))
        assertEquals(WatchFolder.Screenshots, WatchFolder.of("Pictures/Screenshots/"))
        assertEquals(WatchFolder.Screenshots, WatchFolder.of("DCIM/Screenshots/"))
        assertEquals(WatchFolder.Camera, WatchFolder.of("DCIM/Camera/"))
        assertEquals(WatchFolder.Pictures, WatchFolder.of("Pictures/Studio/"))
        assertEquals(ExternalApp.Fv5, WatchFolder.Fv5.likelyApp)
    }

    @Test
    fun expiredSendsAreDropped() {
        assertEquals(listOf(gallery), Matcher.expired(listOf(gallery, notes), 1000 + Matcher.WINDOW_SEC + 50))
    }
}
