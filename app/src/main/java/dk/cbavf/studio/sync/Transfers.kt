package dk.cbavf.studio.sync

import dk.cbavf.studio.link.LinkMessage
import dk.cbavf.studio.link.TransferKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Sends files to the other phone over the Loop link and takes the ones it sends, in both directions
 * at once. A transfer: Offer → Accept (from the byte the receiver already has) → Chunk… → End →
 * Result. The receiver checks the SHA-256 before saving anything.
 *
 * Feed every message the link receives to [handle]; [send] is the link's send.
 */
class Transfers(
    private val incoming: IncomingFiles,
    private val send: suspend (LinkMessage, ByteArray?) -> Unit,
    /** Saves a file that arrived whole and returns what to tell both phones; throw to report a failure. */
    private val onReceived: suspend (kind: TransferKind, name: String, file: File) -> String,
    private val chunkSize: Int = Chunks.SIZE,
    private val replyTimeoutMs: Long = 30_000,
) {
    /** The outcome of a send, in words for the screen. */
    data class Outcome(val ok: Boolean, val text: String)

    private val accepts = HashMap<String, CompletableDeferred<Long>>()
    private val results = HashMap<String, CompletableDeferred<LinkMessage.Result>>()

    /**
     * Sends [file]; suspends until the other phone has saved it (or failed to). Cancelling the
     * coroutine stops the send: the other phone keeps what it has, and the next send of the same
     * file goes on from there. [progress] gets bytes done and the total.
     */
    suspend fun send(file: File, kind: TransferKind, name: String, progress: (Long, Long) -> Unit = { _, _ -> }): Outcome {
        val size = file.length()
        val sha = withContext(Dispatchers.IO) { Checksum.of(file) }
        val id = UUID.randomUUID().toString()
        val accepted = CompletableDeferred<Long>()
        val result = CompletableDeferred<LinkMessage.Result>()
        synchronized(this) {
            accepts[id] = accepted
            results[id] = result
        }
        try {
            send(LinkMessage.Offer(id, kind, name, size, sha), null)
            val from = withTimeoutOrNull(replyTimeoutMs) { accepted.await() } ?: return Outcome(false, "The other phone did not answer.")
            if (from < 0) return Outcome(false, "The other phone refused the file.")
            progress(from, size)
            for (offset in Chunks.offsets(size, from, chunkSize)) {
                val bytes = withContext(Dispatchers.IO) { Chunks.read(file, offset, chunkSize) }
                send(LinkMessage.Chunk(id, offset), bytes)
                progress(offset + bytes.size, size)
            }
            send(LinkMessage.End(id), null)
            // Checking and saving a whole project can take a while on the other side.
            val r = withTimeoutOrNull(replyTimeoutMs * 4) { result.await() } ?: return Outcome(false, "The other phone did not say if it got the file.")
            return Outcome(r.ok, r.text)
        } finally {
            synchronized(this) {
                accepts.remove(id)
                results.remove(id)
            }
        }
    }

    /** Handles a transfer message; false for any other message. */
    suspend fun handle(message: LinkMessage, payload: ByteArray?): Boolean {
        when (message) {
            is LinkMessage.Offer -> {
                val from = withContext(Dispatchers.IO) { incoming.offer(message) }
                send(LinkMessage.Accept(message.id, from), null)
            }
            is LinkMessage.Accept -> synchronized(this) { accepts[message.id] }?.complete(message.from)
            is LinkMessage.Chunk -> withContext(Dispatchers.IO) { incoming.chunk(message.id, message.offset, payload ?: ByteArray(0)) }
            is LinkMessage.End -> {
                val done = withContext(Dispatchers.IO) { incoming.end(message.id) }
                val reply = if (done == null) {
                    LinkMessage.Result(message.id, false, "The file came in damaged. Send it again.")
                } else {
                    val (offer, file) = done
                    try {
                        LinkMessage.Result(message.id, true, onReceived(offer.kind, offer.name, file))
                    } catch (e: Exception) {
                        LinkMessage.Result(message.id, false, "The other phone could not save it: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        withContext(Dispatchers.IO) { file.delete() }
                    }
                }
                send(reply, null)
            }
            is LinkMessage.Result -> synchronized(this) { results[message.id] }?.complete(message)
            else -> return false
        }
        return true
    }
}
