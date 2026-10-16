package dk.cbavf.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * An immutable document with undo, as the rooms keep their edits: every edit replaces [doc], and
 * consecutive edits with the same non-null key (one slider drag) are one undo step.
 */
class Undoable<T>(initial: T, private val maxUndo: Int = 100) {
    var doc by mutableStateOf(initial)
        private set
    var canUndo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<T>()
    private var lastKey: Any? = null

    fun edit(key: Any? = null, f: (T) -> T) {
        val next = f(doc)
        if (next == doc) return
        if (key == null || key != lastKey) {
            history.addLast(doc)
            if (history.size > maxUndo) history.removeFirst()
            canUndo = true
        }
        lastKey = key
        doc = next
    }

    fun undo() {
        doc = history.removeLastOrNull() ?: return
        lastKey = null
        canUndo = history.isNotEmpty()
    }

    /** Opens another document as an undoable edit: Undo brings back what was here. */
    fun open(d: T) = edit { d }

    /** Replaces the document and forgets the history (startup restore). */
    fun load(d: T) {
        doc = d
        history.clear()
        canUndo = false
        lastKey = null
    }
}
