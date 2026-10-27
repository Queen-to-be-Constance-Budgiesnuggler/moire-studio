package dk.cbavf.studio.studio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.math.MathParams
import java.io.File

/**
 * The studio's two user files, read and written as Compose state.
 *
 *  - `user.profile` in the app's main folder (filesDir): who you are, every studio flag and the developer options.
 *  - `user.workspace` in local storage (the app's external files folder, reachable over USB and adb): the open
 *    project and where you left off.
 *  - Project files live in the project folders (`projects/<name>/`), as before.
 *
 * The first time, the old SharedPreferences ("studio" and "loop") are migrated into the two files.
 */
class UserConfigStore(private val context: Context) {
    val profileFile = File(context.filesDir, "user.profile")
    val workspaceFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "user.workspace")

    var profile by mutableStateOf(UserProfile())
        private set
    var workspace by mutableStateOf(UserWorkspace())
        private set

    init {
        if (profileFile.exists() || workspaceFile.exists()) {
            profile = UserConfigJson.decodeProfile(profileFile.readTextOrNull())
            workspace = UserConfigJson.decodeWorkspace(workspaceFile.readTextOrNull())
        } else {
            migrateOldPreferences()
        }
        MathParams.load(profile.mathParams)
    }

    private fun migrateOldPreferences() {
        val studio = context.getSharedPreferences("studio", Context.MODE_PRIVATE).all
        val loop = context.getSharedPreferences("loop", Context.MODE_PRIVATE).all
        val (p, w) = UserConfigJson.migrate(studio, loop)
        profile = p
        workspace = w
        write(profileFile, UserConfigJson.encode(p))
        write(workspaceFile, UserConfigJson.encode(w))
    }

    fun updateProfile(change: (UserProfile) -> UserProfile) {
        profile = UserConfigJson.clean(change(profile))
        write(profileFile, UserConfigJson.encode(profile))
    }

    fun updateWorkspace(change: (UserWorkspace) -> UserWorkspace) {
        workspace = change(workspace)
        write(workspaceFile, UserConfigJson.encode(workspace))
    }

    /** Saves the math parameter overrides into the profile (called when one changes in Studio → Config). */
    fun saveMathParams() = updateProfile { it.copy(mathParams = MathParams.snapshot()) }

    companion object {
        /** The open project's name, read straight from user.workspace (for services that run without the UI). */
        fun currentProject(context: Context): String {
            val f = File(context.getExternalFilesDir(null) ?: context.filesDir, "user.workspace")
            val name = runCatching { UserConfigJson.decodeWorkspace(if (f.exists()) f.readText() else null).project }.getOrNull()
            return name?.takeIf { it.isNotBlank() } ?: "default"
        }
    }

    /** A short description of where each file is, for the Config tab. */
    fun locations(projectDir: File): List<Pair<String, String>> = listOf(
        "user.profile" to profileFile.absolutePath,
        "user.workspace" to workspaceFile.absolutePath,
        "project files" to projectDir.absolutePath,
    )

    private fun File.readTextOrNull(): String? = runCatching { if (exists()) readText() else null }.getOrNull()

    /** Written whole to a temporary file and moved into place, so a crash cannot leave half a file. */
    private fun write(target: File, text: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
        }
    }
}
