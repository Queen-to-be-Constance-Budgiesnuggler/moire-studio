package dk.cbavf.studio.ui.history

/**
 * A labelled history of states, for the listable undo. Entry 0 is the starting state ("Start"). A new action drops
 * whatever could still have been redone, and the oldest entries go when there are more than [limit].
 */
class History<T>(
    initial: T,
    private val limit: Int = 100,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Entry<T>(val label: String, val timeMs: Long, val state: T)

    private val list = ArrayList<Entry<T>>().apply { add(Entry("Start", clock(), initial)) }

    /** Position of the current entry. */
    var index: Int = 0
        private set

    val entries: List<Entry<T>> get() = list.toList()
    val current: T get() = list[index].state
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < list.size - 1

    fun push(label: String, state: T) {
        while (list.size > index + 1) list.removeAt(list.size - 1)
        list.add(Entry(label, clock(), state))
        while (list.size > limit) list.removeAt(0)
        index = list.size - 1
    }

    /** Changes the current state without a new step (one slider drag stays one step). */
    fun replaceCurrent(state: T) {
        list[index] = list[index].copy(state = state)
    }

    fun undo(): T? {
        if (!canUndo) return null
        index--
        return current
    }

    fun redo(): T? {
        if (!canRedo) return null
        index++
        return current
    }

    fun jumpTo(i: Int): T? {
        if (i !in list.indices) return null
        index = i
        return current
    }

    fun labels(): List<String> = list.map { it.label }
}
