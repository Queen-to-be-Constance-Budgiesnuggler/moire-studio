package dk.cbavf.studio.sync

import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One Library asset as a zip, to send to the other phone: `record.json` (its lineage record),
 * `asset.<ext>` (the picture or video) and `recipe.json` (how it was made, if known). Packing the
 * same asset twice gives the same bytes, so a cut-off send can resume. Call off the main thread.
 */
@OptIn(ExperimentalSerializationApi::class)
object AssetBundle {
    fun pack(store: ProjectStore, record: AssetRecord, out: File) {
        val time = record.createdAt
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.entry("record.json", time) { it.write(ProjectStore.json.encodeToString(AssetRecord.serializer(), record).toByteArray()) }
            zip.entry("asset." + record.file.substringAfterLast('.').lowercase(), time) { o -> store.fileOf(record).inputStream().use { it.copyTo(o) } }
            store.recipe(record)?.let { r ->
                zip.entry("recipe.json", time) { it.write(ProjectStore.json.encodeToString(Recipe.serializer(), r).toByteArray()) }
            }
        }
    }

    /**
     * Files the bundle in [store]; the stored record, or null when the Library already had it.
     * Throws [ZipLimits.TooBig] (filing nothing) when the bundle goes past [limits].
     */
    fun unpack(bundle: File, store: ProjectStore, limits: ZipLimits = ZipLimits()): AssetRecord? {
        var record: AssetRecord? = null
        var recipe: Recipe? = null
        val picture = File(store.tempDir, "received-${System.nanoTime()}")
        try {
            ZipInputStream(bundle.inputStream().buffered()).use { zip ->
                val budget = limits.budget()
                while (true) {
                    val e = zip.nextEntry ?: break
                    val input = budget.entry(zip)
                    when {
                        e.name == "record.json" -> record = ProjectStore.json.decodeFromStream(AssetRecord.serializer(), input)
                        e.name == "recipe.json" -> recipe = ProjectStore.json.decodeFromStream(Recipe.serializer(), input)
                        e.name.startsWith("asset.") -> picture.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            val r = record ?: error("No asset in what came")
            check(picture.exists()) { "No picture in what came" }
            return store.addReceived(r, picture, recipe)
        } finally {
            picture.delete()
        }
    }
}

/**
 * A whole project folder as a zip, to send to the other phone. Scratch files are left out, and
 * entries are written in a fixed order with the files' own times, so an unchanged project packs
 * to the same bytes and a cut-off send can resume.
 */
object ProjectZip {
    fun pack(dir: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            files(dir).forEach { f ->
                zip.entry(f.relativeTo(dir).invariantSeparatorsPath, f.lastModified()) { o -> f.inputStream().use { it.copyTo(o) } }
            }
        }
    }

    /** The files that travel: everything but `tmp/`, temp files and set-aside corrupt files. */
    fun files(dir: File): List<File> = dir.walkTopDown()
        .onEnter { it == dir || it.relativeTo(dir).invariantSeparatorsPath != "tmp" }
        .filter { it.isFile && !it.name.endsWith(".tmp") && ".corrupt-" !in it.name }
        .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
        .toList()

    /** A folder name from what was typed: letters, digits, dash, underscore and spaces kept. */
    fun cleanName(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).ifBlank { "project" }

    /** [name] cleaned, with "-2" added until no folder in [root] has it. */
    fun freeName(root: File, name: String): String = generateSequence(cleanName(name)) { "$it-2" }.first { !File(root, it).exists() }

    /**
     * Unpacks into a new project folder under [root]; its name. Entries that would land outside it
     * are skipped. On any failure, [ZipLimits.TooBig] included, the half-made folder is removed.
     */
    fun unpack(input: InputStream, root: File, name: String, limits: ZipLimits = ZipLimits()): String {
        val n = freeName(root, name)
        val base = File(root, n).apply { mkdirs() }
        try {
            ZipInputStream(input.buffered()).use { zip ->
                val budget = limits.budget()
                while (true) {
                    val e = zip.nextEntry ?: break
                    val entry = budget.entry(zip)
                    val out = File(base, e.name).canonicalFile
                    // Zip slip: nothing may land outside the new project.
                    if (!out.path.startsWith(base.canonicalPath + File.separator)) continue
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { entry.copyTo(it) }
                    }
                }
            }
        } catch (e: Throwable) {
            base.deleteRecursively()
            throw e
        }
        return n
    }
}

/**
 * Caps on what a received zip may unpack to, so a bad or hostile file cannot fill the phone:
 * how many entries, how many bytes in all, and how many bytes in one entry.
 */
data class ZipLimits(
    val maxEntries: Int = 10_000,
    val maxTotalBytes: Long = 8L shl 30,
    val maxEntryBytes: Long = 2L shl 30,
) {
    class TooBig(message: String) : java.io.IOException(message)

    fun budget() = Budget()

    /** Counts entries and bytes over one zip. */
    inner class Budget {
        private var entries = 0
        private var total = 0L

        /** The current entry, read through the caps; counts it as one more entry. */
        fun entry(zip: InputStream): InputStream {
            if (++entries > maxEntries) throw TooBig("Too many files in what came (over $maxEntries).")
            return Capped(zip)
        }

        private inner class Capped(private val inner: InputStream) : InputStream() {
            private var read = 0L

            private fun count(n: Int) {
                if (n <= 0) return
                read += n
                total += n
                if (read > maxEntryBytes) throw TooBig("A file in what came is too large.")
                if (total > maxTotalBytes) throw TooBig("What came is too large to unpack.")
            }

            override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also(::count)

            /** Leaves the zip open for the next entry (a JSON decoder closes what it reads). */
            override fun close() = Unit
        }
    }
}

private fun ZipOutputStream.entry(name: String, time: Long, write: (OutputStream) -> Unit) {
    putNextEntry(ZipEntry(name).apply { this.time = time })
    write(this)
    closeEntry()
}
