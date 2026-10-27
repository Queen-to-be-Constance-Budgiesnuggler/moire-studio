package dk.cbavf.studio.studio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dk.cbavf.studio.ui.StudioTheme
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Studio-wide preferences, read as Compose state. They live in the two user files (see [UserConfigStore]):
 * `user.profile` for who you are and every studio flag, `user.workspace` for the open project and last room.
 */
class StudioPrefs(context: Context) {
    val config = UserConfigStore(context)

    val theme: StudioTheme get() = runCatching { StudioTheme.valueOf(config.profile.theme) }.getOrDefault(StudioTheme.Studio)
    fun updateTheme(t: StudioTheme) = config.updateProfile { it.copy(theme = t.name) }

    /** Size of everything on screen, 0.8–1.4. */
    val uiScale: Float get() = config.profile.uiScale
    fun updateUiScale(v: Float) = config.updateProfile { it.copy(uiScale = v) }

    /** Room to open at start: "last" (where you left off) or a room name. */
    val startRoom: String get() = config.profile.startRoom.ifBlank { "last" }
    fun updateStartRoom(v: String) = config.updateProfile { it.copy(startRoom = v) }

    val keepScreenOn: Boolean get() = config.profile.keepScreenOn
    fun updateKeepScreenOn(v: Boolean) = config.updateProfile { it.copy(keepScreenOn = v) }

    val haptics: Boolean get() = config.profile.haptics
    fun updateHaptics(v: Boolean) = config.updateProfile { it.copy(haptics = v) }

    // Profile.
    val name: String get() = config.profile.name
    val handle: String get() = config.profile.handle
    val about: String get() = config.profile.about
    fun setProfile(name: String, handle: String, about: String) =
        config.updateProfile { it.copy(name = name.trim(), handle = handle.trim(), about = about.trim()) }

    /** The project the studio works in (a folder under files/projects). */
    val project: String get() = config.workspace.project.ifBlank { "default" }

    /** Width of the controls panel beside the picture in landscape, in dp (0 = the default). */
    val panelWidthDp: Float get() = config.workspace.panelWidthDp
    fun setPanelWidthDp(v: Float) = config.updateWorkspace { it.copy(panelWidthDp = v) }

    val modulations: Map<String, dk.cbavf.studio.modulation.ControlModulation> get() = config.workspace.modulations
    fun saveModulations(m: Map<String, dk.cbavf.studio.modulation.ControlModulation>) = config.updateWorkspace { it.copy(modulations = m) }

    /** Written at once (not later), because the screen is recreated right after switching. */
    fun setProject(name: String) = config.updateWorkspace { it.copy(project = name) }
}

/** The studio's projects: folders under files/projects, each a complete Library with its rooms. */
class Projects(private val context: Context) {
    val root = File(context.filesDir, "projects")

    fun list(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    fun dir(name: String) = File(root, name)

    /** A folder name from what was typed: letters, digits, dash, underscore and spaces kept. */
    fun clean(name: String): String = dk.cbavf.studio.sync.ProjectZip.cleanName(name)

    fun create(name: String): String? {
        val n = clean(name)
        val d = dir(n)
        if (d.exists()) return null
        return n.takeIf { d.mkdirs() }
    }

    fun duplicate(from: String, to: String): String? {
        val n = clean(to)
        if (dir(n).exists()) return null
        dir(from).copyRecursively(dir(n))
        return n
    }

    fun rename(from: String, to: String): String? {
        val n = clean(to)
        if (dir(n).exists()) return null
        return n.takeIf { dir(from).renameTo(dir(n)) }
    }

    fun delete(name: String): Boolean = dir(name).deleteRecursively()

    /** Bytes the project takes and how many files it holds. */
    fun size(name: String): Pair<Long, Int> {
        var bytes = 0L
        var n = 0
        dir(name).walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); n++ }
        return bytes to n
    }

    /** The whole project as a .zip in Download/Studio; its content uri, or null. */
    fun export(name: String, extras: Map<String, String> = emptyMap()): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "moire-studio-$name.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Studio")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val base = dir(name)
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                base.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(base).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                // Extra files (the maker's profile) next to the project's own.
                extras.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        } ?: return null
        return uri
    }

    /** Unpacks a project .zip into a new project named [name]; entries outside the folder are skipped. */
    fun import(uri: Uri, name: String): String? =
        context.contentResolver.openInputStream(uri)?.use { dk.cbavf.studio.sync.ProjectZip.unpack(it, root, name) }
}
