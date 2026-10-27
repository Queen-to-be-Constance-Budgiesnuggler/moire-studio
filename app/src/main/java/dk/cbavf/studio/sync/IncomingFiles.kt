package dk.cbavf.studio.sync

import dk.cbavf.studio.link.LinkMessage
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** SHA-256 as lowercase hex. */
object Checksum {
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun of(file: File): String = file.inputStream().use(::of)

    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun isValid(sha: String): Boolean = SHA.matches(sha)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val SHA = Regex("[0-9a-f]{64}")
}

/** Where a file is cut for sending: the start of each chunk from [from] on. */
object Chunks {
    const val SIZE = 256 * 1024

    fun offsets(size: Long, from: Long, chunk: Int = SIZE): List<Long> {
        require(chunk > 0)
        if (from >= size) return emptyList()
        return generateSequence(from.coerceAtLeast(0)) { it + chunk }.takeWhile { it < size }.toList()
    }

    /** The bytes of the chunk at [offset]. */
    fun read(file: File, offset: Long, chunk: Int = SIZE): ByteArray = RandomAccessFile(file, "r").use { f ->
        val n = minOf(chunk.toLong(), f.length() - offset).toInt()
        ByteArray(n).also {
            f.seek(offset)
            f.readFully(it)
        }
    }
}

/**
 * The receiving side of transfers: each file is written to `<dir>/<sha256>.part` as its chunks come,
 * so a transfer cut off (a dropped link, the studio sent to the background) goes on from where it
 * stopped when the same file is offered again. Plain files only; call it off the main thread.
 */
class IncomingFiles(private val dir: File) {
    private class Open(val offer: LinkMessage.Offer, val part: File)

    private val open = HashMap<String, Open>()

    /** Starts (or picks up) [offer]; returns the byte to go on from, or -1 to refuse it. */
    @Synchronized
    fun offer(offer: LinkMessage.Offer): Long {
        if (!Checksum.isValid(offer.sha256) || offer.size < 0 || offer.size > MAX_SIZE) return -1
        dir.mkdirs()
        val part = File(dir, "${offer.sha256}.part")
        // A part longer than the file is not this file: start again.
        if (part.length() > offer.size) part.delete()
        if (!part.exists()) part.createNewFile()
        part.setLastModified(System.currentTimeMillis())
        open[offer.id] = Open(offer, part)
        return part.length()
    }

    /** Appends a chunk; false (writing nothing) when it is not the next one of an open transfer. */
    @Synchronized
    fun chunk(id: String, offset: Long, bytes: ByteArray): Boolean {
        val o = open[id] ?: return false
        if (offset != o.part.length() || offset + bytes.size > o.offer.size) return false
        java.io.FileOutputStream(o.part, true).use { it.write(bytes) }
        return true
    }

    /** How many bytes of [id] are here so far (0 when unknown). */
    @Synchronized
    fun received(id: String): Long = open[id]?.part?.length() ?: 0

    /**
     * The transfer is over: the whole file, renamed from `.part` to `.done` once its checksum
     * matches, else null. A file that fails the check is thrown away, so the next try starts clean.
     * The caller deletes the returned file once it is saved.
     */
    @Synchronized
    fun end(id: String): Pair<LinkMessage.Offer, File>? {
        val o = open.remove(id) ?: return null
        if (o.part.length() != o.offer.size) return null
        if (Checksum.of(o.part) != o.offer.sha256) {
            o.part.delete()
            return null
        }
        val done = File(dir, "${o.offer.sha256}.done")
        done.delete()
        check(o.part.renameTo(done)) { "Could not keep the received file" }
        return o.offer to done
    }

    /** Removes parts nobody has offered again for [maxAgeMs], and finished files left behind by a crash. */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis(), maxAgeMs: Long = WEEK_MS) {
        dir.listFiles()
            ?.filter { (it.name.endsWith(".part") || it.name.endsWith(".done")) && now - it.lastModified() > maxAgeMs }
            ?.forEach { it.delete() }
    }

    companion object {
        /** Larger than any project worth sending this way. */
        const val MAX_SIZE = 8L shl 30
        private const val WEEK_MS = 7L * 24 * 3600 * 1000
    }
}
