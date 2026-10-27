package dk.cbavf.studio.feedback

import java.io.File

/**
 * Reports waiting to go out, one JSON file each in [dir]. Bounded, so a phone that is never online cannot fill itself:
 * past [maxFiles] files or [maxBytes] bytes the oldest are dropped.
 */
class FeedbackQueue(private val dir: File, private val maxFiles: Int = 40, private val maxBytes: Long = 2L * 1024 * 1024) {
    init { dir.mkdirs() }

    /** Puts [json] at the back of the queue. Written to a temporary name first, so a half-written file is never picked up. */
    @Synchronized fun add(json: String): File {
        val name = "%013d-%s.json".format(System.currentTimeMillis(), java.lang.Long.toHexString(System.nanoTime()))
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json)
        val f = File(dir, name)
        tmp.renameTo(f)
        trim()
        return f
    }

    /** Waiting files, oldest first. */
    @Synchronized fun pending(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    @Synchronized fun remove(f: File) { f.delete() }

    /** Deletes every waiting file ("Delete my data" and switching to Off). */
    @Synchronized fun clear() { pending().forEach { it.delete() }; dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() } }

    private fun trim() {
        var files = pending()
        while (files.size > maxFiles) { files.first().delete(); files = pending() }
        var total = files.sumOf { it.length() }
        while (total > maxBytes && files.size > 1) { total -= files.first().length(); files.first().delete(); files = pending() }
    }
}
