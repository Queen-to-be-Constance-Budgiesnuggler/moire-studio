package dk.cbavf.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.ui.history.History

/** A list of what was done, that can be jumped around in (the History panel). */
interface HistoryView {
    /** Every state, oldest first: "Start" and then one label per action, including undone ones that can be redone. */
    val timeline: List<String>

    /** Which entry of [timeline] is the current state. */
    val position: Int

    fun jumpTo(i: Int)
}

/**
 * An immutable document with undo and redo, as the rooms keep their edits: every edit replaces
 * [doc], and consecutive edits with the same non-null key (one slider drag) are one undo step.
 * A new edit after undoing drops what could have been redone. Every step has a label, listed in the
 * History panel, where any step can be jumped to.
 */
class Undoable<T>(initial: T, private val maxUndo: Int = 100) : HistoryView {
    private var h = History(initial, limit = maxUndo + 1)
    private var version by mutableIntStateOf(0)
    private var lastKey: Any? = null

    val doc: T get() { version; return h.current }
    val canUndo: Boolean get() { version; return h.canUndo }
    val canRedo: Boolean get() { version; return h.canRedo }

    override val timeline: List<String> get() { version; return h.labels() }
    override val position: Int get() { version; return h.index }

    fun edit(key: Any? = null, label: String? = null, f: (T) -> T) {
        val next = f(doc)
        if (next == doc) return
        if (key != null && key == lastKey && h.canUndo && !h.canRedo) h.replaceCurrent(next)
        else h.push(label ?: if (key == null) "Edit" else "Adjust", next)
        lastKey = key
        version++
    }

    fun undo() {
        if (h.undo() == null) return
        lastKey = null
        version++
    }

    fun redo() {
        if (h.redo() == null) return
        lastKey = null
        version++
    }

    override fun jumpTo(i: Int) {
        if (h.jumpTo(i) == null) return
        lastKey = null
        version++
    }

    /** Opens another document as an undoable edit: Undo brings back what was here. */
    fun open(d: T, label: String = "Open") = edit(label = label) { d }

    /** Replaces the document without an undo step (bookkeeping the user did not do, such as new controls appearing). */
    fun replace(d: T) {
        h.replaceCurrent(d)
        version++
    }

    /** Replaces the document and forgets the history (startup restore). */
    fun load(d: T) {
        h = History(d, limit = maxUndo + 1)
        lastKey = null
        version++
    }
}
