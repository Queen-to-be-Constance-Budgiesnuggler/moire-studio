package dk.cbavf.studio.plugins

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dk.cbavf.studio.library.exportToMedia
import java.io.File

/** The plugins folder (filesDir/plugins): one folder per installed plugin, with its manifest.json. */
class PluginStore(private val context: Context) {
    private val root = File(context.filesDir, "plugins")

    fun installed(): List<PluginManifest> =
        root.listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { dir -> PluginFormat.decode(runCatching { File(dir, PluginFormat.MANIFEST).readText() }.getOrNull()) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /** Saves (or replaces) a plugin made here, in the Module Forge. */
    fun save(m: PluginManifest) {
        val dir = File(root, m.id).apply { mkdirs() }
        File(dir, PluginFormat.MANIFEST).writeText(PluginFormat.encode(m))
    }

    fun remove(id: String) {
        File(root, id).takeIf { it.isDirectory && it.parentFile == root }?.deleteRecursively()
    }

    /** Installs a `.studiomodule` zip; null if it holds no valid manifest. Entries that could leave the plugin's folder are skipped. */
    fun install(uri: Uri): PluginManifest? {
        val files = context.contentResolver.openInputStream(uri)?.use { PluginFormat.unzip(it, MAX_FILES, MAX_FILE_BYTES) } ?: return null
        val manifest = PluginFormat.decode(files[PluginFormat.MANIFEST]?.toString(Charsets.UTF_8)) ?: return null
        val dir = File(root, manifest.id).apply { deleteRecursively(); mkdirs() }
        for ((name, bytes) in files) {
            val target = File(dir, name)
            // Belt and braces after safeEntry: the file must really be inside the plugin's folder.
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
            target.parentFile?.mkdirs()
            // The manifest is written as read back (its behaviour cleaned), not as it came.
            if (name == PluginFormat.MANIFEST) target.writeText(PluginFormat.encode(manifest)) else target.writeBytes(bytes)
        }
        return manifest
    }

    /** Writes a `.studiomodule` zip to the shared media folder so it can be sent to another phone. */
    fun export(m: PluginManifest): Uri? = exportToMedia(context, "${m.id}.${PluginFormat.EXTENSION}", PluginFormat.MIME) { out ->
        PluginFormat.zip(m, out)
    }

    /**
     * Writes a `.studiomodule` zip into the cache (cache/shared) and returns a content:// uri other apps may read, for
     * Android's share sheet. Call it off the main thread.
     */
    fun shareFile(m: PluginManifest): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${m.id}.${PluginFormat.EXTENSION}")
        file.outputStream().use { PluginFormat.zip(m, it) }
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    companion object {
        private const val MAX_FILES = 64
        private const val MAX_FILE_BYTES = 2_000_000

        /** Opens Android's share sheet for a plugin file made by [shareFile]; false if no app can take it. */
        fun openShareSheet(context: Context, m: PluginManifest, uri: Uri): Boolean {
            val send = Intent(Intent.ACTION_SEND)
                .setType(PluginFormat.MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "${m.name} (Moiré Studio plugin)")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, "Share ${m.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return try {
                context.startActivity(chooser)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }

        /** Plugins that come with the studio. */
        val BUILT_IN = listOf(
            PluginManifest(
                id = "rgb-parade", name = "RGB parade", version = "1.1", author = "Moiré Studio",
                kind = PluginKind.Scope, scope = PluginScopes.RGB_PARADE,
                description = "Red, green and blue as three waveforms side by side. It shows the picture patched into the " +
                    "Plugins unit's Scope in (pick it in the Plugins room).",
            ),
        )
    }
}
