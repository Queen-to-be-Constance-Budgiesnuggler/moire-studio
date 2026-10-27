package dk.cbavf.studio.sync

import dk.cbavf.studio.link.Link
import dk.cbavf.studio.link.LinkConnection
import dk.cbavf.studio.link.LinkMessage
import dk.cbavf.studio.link.TransferKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Transfers between two links over a loopback socket, as between two phones. */
class TransfersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val data = ByteArray(1000) { (it * 13 + 5).toByte() }

    private class Side(val conn: LinkConnection, val transfers: Transfers, val incomingDir: File)

    /** Both ends of a link, each feeding what it receives to its own [Transfers]. */
    private suspend fun CoroutineScope.pair(
        received: MutableList<Pair<String, ByteArray>>,
        chunksSent: AtomicInteger = AtomicInteger(),
        damage: Boolean = false,
        save: (String, File) -> String = { name, file -> received += name to file.readBytes(); "Saved" },
    ): Pair<Side, Side> {
        val server = Link.listen(port = 0)
        val accepted = async { Link.accept(server) }
        val a = Link.connect("127.0.0.1", server.localPort)
        val b = accepted.await()
        server.close()
        fun side(conn: LinkConnection): Side {
            val dir = tmp.newFolder()
            val t = Transfers(
                IncomingFiles(dir),
                send = { m, p ->
                    if (m is LinkMessage.Chunk) chunksSent.incrementAndGet()
                    conn.send(m, if (damage && m is LinkMessage.Chunk) p?.copyOf()?.also { it[0] = (it[0] + 1).toByte() } else p)
                },
                onReceived = { _, name, file -> save(name, file) },
                chunkSize = 100,
                replyTimeoutMs = 5_000,
            )
            launch(Dispatchers.IO) { runCatching { while (true) conn.receive().let { (m, p) -> t.handle(m, p) } } }
            return Side(conn, t, dir)
        }
        return side(a) to side(b)
    }

    private fun file(bytes: ByteArray = data) = tmp.newFile().apply { writeBytes(bytes) }

    private fun close(vararg s: Side) = s.forEach { it.conn.close() }

    @Test(timeout = 20_000)
    fun aFileCrossesTheLinkWhole() = runBlocking {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val (a, b) = pair(received)
        val outcome = a.transfers.send(file(), TransferKind.Asset, "pic")
        close(a, b)
        assertTrue(outcome.ok)
        assertEquals("Saved", outcome.text)
        assertEquals("pic", received.single().first)
        assertArrayEquals(data, received.single().second)
    }

    @Test(timeout = 20_000)
    fun bothPhonesCanSend() = runBlocking {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val (a, b) = pair(received)
        assertTrue(a.transfers.send(file(), TransferKind.Asset, "one").ok)
        assertTrue(b.transfers.send(file(), TransferKind.Project, "two").ok)
        close(a, b)
        assertEquals(listOf("one", "two"), received.map { it.first })
    }

    @Test(timeout = 20_000)
    fun aCutOffSendGoesOnFromWhereItStopped() = runBlocking {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val chunks = AtomicInteger()
        val (a, b) = pair(received, chunks)
        // The receiver already has the first 300 bytes from an earlier try.
        File(b.incomingDir, "${Checksum.of(data)}.part").writeBytes(data.copyOfRange(0, 300))
        val progress = mutableListOf<Long>()
        val outcome = a.transfers.send(file(), TransferKind.Asset, "pic") { done, _ -> progress += done }
        close(a, b)
        assertTrue(outcome.ok)
        assertEquals(7, chunks.get())
        assertEquals(300L, progress.first())
        assertEquals(1000L, progress.last())
        assertArrayEquals(data, received.single().second)
    }

    @Test(timeout = 20_000)
    fun aDamagedFileIsNotSaved() = runBlocking {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val (a, b) = pair(received, damage = true)
        val outcome = a.transfers.send(file(), TransferKind.Asset, "pic")
        close(a, b)
        assertFalse(outcome.ok)
        assertTrue(received.isEmpty())
    }

    @Test(timeout = 20_000)
    fun aSaveThatFailsIsReportedToTheSender() = runBlocking {
        val (a, b) = pair(mutableListOf(), save = { _, _ -> error("disk full") })
        val outcome = a.transfers.send(file(), TransferKind.Project, "p")
        close(a, b)
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("disk full"))
    }

    @Test(timeout = 20_000)
    fun anEmptyFileStillArrives() = runBlocking {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val (a, b) = pair(received)
        assertTrue(a.transfers.send(file(ByteArray(0)), TransferKind.Asset, "empty").ok)
        close(a, b)
        assertEquals(0, received.single().second.size)
    }

    @Test(timeout = 10_000)
    fun noAnswerEndsTheSend() = runBlocking {
        val t = Transfers(IncomingFiles(tmp.newFolder()), send = { _, _ -> }, onReceived = { _, _, _ -> "" }, replyTimeoutMs = 200)
        val outcome = t.send(file(), TransferKind.Asset, "pic")
        assertFalse(outcome.ok)
        assertEquals("The other phone did not answer.", outcome.text)
    }

    @Test(timeout = 10_000)
    fun otherMessagesAreLeftToTheLoop() = runBlocking {
        val t = Transfers(IncomingFiles(tmp.newFolder()), send = { _, _ -> }, onReceived = { _, _, _ -> "" })
        assertFalse(t.handle(LinkMessage.Stop, null))
    }
}
