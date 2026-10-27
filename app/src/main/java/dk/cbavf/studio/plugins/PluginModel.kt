package dk.cbavf.studio.plugins

import dk.cbavf.studio.uieditor.ControlKind
import dk.cbavf.studio.uieditor.ControlSeed
import dk.cbavf.studio.uieditor.ModuleSeed
import dk.cbavf.studio.nodes.model.Graph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a plugin is for: a scope or visualizer, an interactive controller, or an image processor. */
@Serializable
enum class PluginKind { Scope, Controller, Processor }

/** One control of a plugin's face. A slider runs from [min] to [max]. */
@Serializable
data class PluginWidget(val label: String, val kind: ControlKind = ControlKind.Slider, val min: Float = 0f, val max: Float = 1f)

/**
 * A plugin or custom module as it is saved: `manifest.json` inside a `.studiomodule` zip, and in the
 * studio's plugins folder once installed. Behaviour comes from a node graph ([behaviour]), never from code.
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val author: String = "",
    val description: String = "",
    val kind: PluginKind = PluginKind.Controller,
    val widgets: List<PluginWidget> = emptyList(),
    /** The format of this file, for later changes. */
    val format: Int = 1,
    /**
     * What the controls do: a Node Lab graph of number nodes, from a Group Input (the controls) to a Group Output (the
     * plugin's outputs). See [PluginBehaviour]. Null: the controls are just controls.
     */
    val behaviour: Graph? = null,
    /** For a scope: which of the studio's scopes it shows ([PluginScopes]), or null. */
    val scope: String? = null,
)

object PluginFormat {
    const val EXTENSION = "studiomodule"
    const val MANIFEST = "manifest.json"
    const val MIME = "application/zip"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** A file-safe id from a name: lowercase letters and digits joined by dashes. */
    fun idFor(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "plugin" }

    fun encode(m: PluginManifest): String = json.encodeToString(PluginManifest.serializer(), m)

    /**
     * The manifest in [text], or null if it is not one (not JSON, no id or name, or an id that is not a safe folder name).
     * A behaviour graph is cleaned on the way in: only number nodes, and no more than [PluginBehaviour.MAX_STEPS].
     */
    fun decode(text: String?): PluginManifest? {
        if (text.isNullOrBlank()) return null
        val m = runCatching { json.decodeFromString(PluginManifest.serializer(), text) }.getOrNull() ?: return null
        if (m.name.isBlank() || m.id != idFor(m.id) || m.id.isBlank()) return null
        return m.behaviour?.let { m.copy(behaviour = PluginBehaviour.sync(m.widgets, it)) } ?: m
    }

    /** Writes [m] as a `.studiomodule` zip to [out] (left open). */
    fun zip(m: PluginManifest, out: OutputStream) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(encode(m).toByteArray())
        zip.closeEntry()
        zip.finish()
    }

    /**
     * The files of a `.studiomodule` zip read from [input]: at most [maxFiles] files of at most [maxBytes] each. Entries
     * that could leave the plugin's folder (see [safeEntry]) and files that are too big are skipped.
     */
    fun unzip(input: InputStream, maxFiles: Int = 64, maxBytes: Int = 2_000_000): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        val zip = ZipInputStream(input)
        var e: ZipEntry? = zip.nextEntry
        while (e != null) {
            val name = safeEntry(e.name)
            if (!e.isDirectory && name != null && files.size < maxFiles) zip.readLimited(maxBytes)?.let { files[name] = it }
            e = zip.nextEntry
        }
        return files
    }

    /** Reads the current zip entry, or null if it is bigger than [max] bytes. */
    private fun ZipInputStream.readLimited(max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** A zip entry name that stays inside the folder it is unpacked to, or null if it could escape (zip-slip). */
    fun safeEntry(name: String): String? {
        val n = name.replace('\\', '/')
        if (n.isBlank() || n.startsWith("/") || n.contains(':')) return null
        if (n.split('/').any { it == ".." }) return null
        return n
    }

    /** The plugin's controls as a module for the UI Editor, then its behaviour's outputs (as sliders that show the output). */
    fun seed(m: PluginManifest): ModuleSeed = ModuleSeed(
        "plugin-${m.id}", m.name,
        m.widgets.map { ControlSeed(it.label, it.kind) } + PluginBehaviour.outputs(m).map { ControlSeed(PluginBehaviour.outputLabel(it.name), ControlKind.Slider) },
    )
}
