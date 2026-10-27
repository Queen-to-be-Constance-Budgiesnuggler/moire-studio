package dk.cbavf.studio.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.bridge.model.BridgeDoc
import dk.cbavf.studio.bridge.model.Candidate
import dk.cbavf.studio.bridge.model.ExternalApp
import dk.cbavf.studio.bridge.model.Matcher
import dk.cbavf.studio.bridge.model.PendingSend

/**
 * Bridge room state: sends waiting for something to come back, and the inbox of new images
 * found in shared storage. Only [doc] is persisted; the inbox is rescanned from it.
 */
class BridgeState {
    var doc by mutableStateOf(BridgeDoc())
        private set

    /** New images since [BridgeDoc.watchSinceSec] that are not handled yet. */
    val inbox = mutableStateListOf<Candidate>()

    var hasMediaAccess by mutableStateOf(false)

    fun load(d: BridgeDoc) {
        doc = d
    }

    /** First run: watch from now on, so the whole existing gallery doesn't flood the inbox. */
    fun startWatchingIfNeeded(nowSec: Long) {
        if (doc.watchSinceSec == 0L) doc = doc.copy(watchSinceSec = nowSec)
    }

    fun recordSend(app: ExternalApp, assetId: String?, nowSec: Long) {
        // Sends that can no longer match anything are dropped as new ones come in.
        val live = doc.sends - Matcher.expired(doc.sends, nowSec).toSet()
        doc = doc.copy(sends = live + PendingSend(app, assetId, nowSec))
    }

    fun clearSends() {
        doc = doc.copy(sends = emptyList())
    }

    fun setInbox(candidates: List<Candidate>) {
        inbox.clear()
        inbox.addAll(candidates)
    }

    /** Imported or dismissed: never shown again. */
    fun handled(c: Candidate) {
        doc = doc.copy(handled = doc.handled + c.mediaId)
        inbox.removeAll { it.mediaId == c.mediaId }
    }

    /** Dismisses everything in the inbox by moving the watch start past it. */
    fun clearInbox() {
        val newest = inbox.maxOfOrNull { it.addedAtSec } ?: return
        doc = doc.copy(watchSinceSec = newest)
        inbox.clear()
    }
}
