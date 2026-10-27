package dk.cbavf.studio.bridge

import android.os.Environment
import android.os.FileObserver
import dk.cbavf.studio.debug.Debug
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.foreground.RunGate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Watches the Bridge folders on the filesystem for new image files, using one [FileObserver] per
 * existing directory. Returns a [Flow] that emits [Unit] whenever a new image is detected.
 *
 * The observers are started and stopped through a [RunGate.Hook] registered on [Foreground.gate],
 * so nothing runs while the app is in the background.
 */
fun folderChanges(): Flow<Unit> = callbackFlow {
    val observers = mutableListOf<FileObserver>()
    val root = Environment.getExternalStorageDirectory()

    fun startObservers() {
        for ((folder, subPath) in BridgeWatcher.watchPaths()) {
            val dir = File(root, subPath)
            if (!dir.isDirectory) continue
            val obs = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (BridgeWatcher.isImageEvent(event, path)) {
                        Debug.t("Bridge", "folder observer: $path in ${folder.label}")
                        trySend(Unit)
                    }
                }
            }
            obs.startWatching()
            observers += obs
        }
        Debug.t("Bridge", "folder observers started: ${observers.size} directories")
    }

    fun stopObservers() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        Debug.t("Bridge", "folder observers stopped")
    }

    val hook = object : RunGate.Hook {
        override fun onStart() = startObservers()
        override fun onStop() = stopObservers()
    }

    val remove = Foreground.gate.add(hook)
    // If the gate is already started, add() does not call onStart, so start manually.
    if (Foreground.gate.isStarted) startObservers()

    awaitClose {
        stopObservers()
        remove()
    }
}
