package dk.cbavf.studio.sync

import dk.cbavf.studio.link.LinkMessage
import dk.cbavf.studio.link.TransferKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IncomingFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val data = ByteArray(1000) { (it * 7).toByte() }
    private fun offer(id: String = "t1", bytes: ByteArray = data) =
        LinkMessage.Offer(id, TransferKind.Asset, "x", bytes.size.toLong(), Checksum.of(bytes))

    @Test
    fun chunksCoverTheFileFromTheResumePoint() {
        assertEquals(listOf(0L, 400L, 800L), Chunks.offsets(1000, 0, 400))
        assertEquals(listOf(400L, 800L), Chunks.offsets(1000, 400, 400))
        assertEquals(emptyList<Long>(), Chunks.offsets(1000, 1000, 400))
        assertEquals(emptyList<Long>(), Chunks.offsets(0, 0, 400))
    }

    @Test
    fun lastChunkIsShort() {
        val f = tmp.newFile().apply { writeBytes(data) }
        assertEquals(200, Chunks.read(f, 800, 400).size)
        assertArrayEquals(data.copyOfRange(400, 800), Chunks.read(f, 400, 400))
    }

    @Test
    fun checksumOfFileMatchesChecksumOfBytes() {
        val f = tmp.newFile().apply { writeBytes(data) }
        assertEquals(Checksum.of(data), Checksum.of(f))
        assertTrue(Checksum.isValid(Checksum.of(data)))
        assertFalse(Checksum.isValid("../../etc"))
    }

    @Test
    fun fileInChunksArrivesWhole() {
        val inc = IncomingFiles(tmp.newFolder())
        assertEquals(0, inc.offer(offer()))
        assertTrue(inc.chunk("t1", 0, data.copyOfRange(0, 600)))
        assertTrue(inc.chunk("t1", 600, data.copyOfRange(600, 1000)))
        val (o, file) = inc.end("t1")!!
        assertEquals("t1", o.id)
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun aCheckedFileIsNoLongerAPart() {
        val dir = tmp.newFolder()
        val inc = IncomingFiles(dir)
        inc.offer(offer())
        inc.chunk("t1", 0, data)
        val file = inc.end("t1")!!.second
        assertTrue(file.name.endsWith(".done"))
        assertEquals(listOf(file.name), dir.list()!!.toList())
    }

    @Test
    fun outOfOrderChunkIsRefused() {
        val inc = IncomingFiles(tmp.newFolder())
        inc.offer(offer())
        assertFalse(inc.chunk("t1", 600, data.copyOfRange(600, 1000)))
        assertEquals(0, inc.received("t1"))
    }

    @Test
    fun chunkPastTheEndIsRefused() {
        val inc = IncomingFiles(tmp.newFolder())
        inc.offer(offer())
        assertFalse(inc.chunk("t1", 0, ByteArray(1001)))
    }

    @Test
    fun chunkForAnUnknownTransferIsRefused() {
        assertFalse(IncomingFiles(tmp.newFolder()).chunk("nope", 0, data))
    }

    @Test
    fun aSecondOfferOfTheSameFileResumes() {
        val dir = tmp.newFolder()
        val first = IncomingFiles(dir)
        first.offer(offer("t1"))
        first.chunk("t1", 0, data.copyOfRange(0, 300))
        // The link dropped; a new session offers the same file under a new id.
        val again = IncomingFiles(dir)
        assertEquals(300, again.offer(offer("t2")))
        assertTrue(again.chunk("t2", 300, data.copyOfRange(300, 1000)))
        assertArrayEquals(data, again.end("t2")!!.second.readBytes())
    }

    @Test
    fun aDamagedFileIsThrownAway() {
        val dir = tmp.newFolder()
        val inc = IncomingFiles(dir)
        inc.offer(offer())
        inc.chunk("t1", 0, ByteArray(1000))
        assertNull(inc.end("t1"))
        assertEquals(0, inc.offer(offer("t2")))
    }

    @Test
    fun anUnfinishedFileIsKeptForLater() {
        val inc = IncomingFiles(tmp.newFolder())
        inc.offer(offer())
        inc.chunk("t1", 0, data.copyOfRange(0, 500))
        assertNull(inc.end("t1"))
        assertEquals(500, inc.offer(offer("t2")))
    }

    @Test
    fun aPartLongerThanTheFileStartsAgain() {
        val dir = tmp.newFolder()
        File(dir, "${Checksum.of(data)}.part").writeBytes(ByteArray(2000))
        assertEquals(0, IncomingFiles(dir).offer(offer()))
    }

    @Test
    fun anOfferWithABadChecksumIsRefused() {
        val inc = IncomingFiles(tmp.newFolder())
        assertEquals(-1, inc.offer(LinkMessage.Offer("t1", TransferKind.Asset, "x", 10, "../x")))
    }

    @Test
    fun oldPartsArePruned() {
        val dir = tmp.newFolder()
        val old = File(dir, "a.part").apply { writeBytes(data); setLastModified(1_000) }
        val fresh = File(dir, "b.part").apply { writeBytes(data) }
        IncomingFiles(dir).prune()
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
