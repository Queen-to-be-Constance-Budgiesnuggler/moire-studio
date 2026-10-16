package dk.cbavf.studio.sequence

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.animate.model.Frame
import dk.cbavf.studio.sequence.model.AssetSource
import dk.cbavf.studio.sequence.model.CanvasSource
import dk.cbavf.studio.sequence.model.FrameFit
import dk.cbavf.studio.sequence.model.FrameShape
import dk.cbavf.studio.sequence.model.PlayMode
import dk.cbavf.studio.sequence.model.SeqItem
import dk.cbavf.studio.sequence.model.SequenceDoc
import kotlin.math.roundToInt

/** Timeline state for the Sequence room; edits replace items, undo keeps snapshots. */
class SequenceState {
    val items = mutableStateListOf<SeqItem>()
    var selected by mutableIntStateOf(0)
    var rateHz by mutableIntStateOf(60)
    var mode by mutableStateOf(PlayMode.Loop)
    var blackTicks by mutableIntStateOf(0)
    var shape by mutableStateOf(FrameShape.Screen)
        private set

    var previewing by mutableStateOf(false)

    var canUndo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<SequenceDoc>()
    private var lastKey: Any? = null

    val selectedItem: SeqItem? get() = items.getOrNull(selected)

    fun toDoc() = SequenceDoc(items.toList(), rateHz, mode, blackTicks, shape)

    fun load(doc: SequenceDoc) {
        items.clear()
        items.addAll(doc.items)
        rateHz = doc.rateHz
        mode = doc.mode
        blackTicks = doc.blackTicks
        shape = doc.shape
        selected = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        history.clear()
        canUndo = false
        lastKey = null
    }

    /** Opens a timeline as an undoable edit (Library → Open in Sequence). */
    fun open(doc: SequenceDoc) {
        checkpoint()
        items.clear()
        items.addAll(doc.items)
        rateHz = doc.rateHz
        mode = doc.mode
        blackTicks = doc.blackTicks
        shape = doc.shape
        selected = 0
    }

    /** Consecutive checkpoints with the same non-null [key] (one slider drag) are one undo step. */
    fun checkpoint(key: Any? = null) {
        if (key != null && key == lastKey) return
        lastKey = key
        history.addLast(toDoc())
        if (history.size > MAX_UNDO) history.removeFirst()
        canUndo = true
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        items.clear()
        items.addAll(previous.items)
        rateHz = previous.rateHz
        mode = previous.mode
        blackTicks = previous.blackTicks
        shape = previous.shape
        lastKey = null
        selected = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        canUndo = history.isNotEmpty()
    }

    private fun insertAll(new: List<SeqItem>) {
        if (new.isEmpty()) return
        checkpoint()
        val at = if (items.isEmpty()) 0 else selected + 1
        items.addAll(at, new)
        selected = at + new.size - 1
    }

    /** Library images, [ticks] each, after the selected item. */
    fun addAssets(ids: List<String>, ticks: Int = rateHz / 6) =
        insertAll(ids.map { SeqItem(AssetSource(it), ticks.coerceAtLeast(1)) })

    /** Animate frames, keeping their timing: hold / fps seconds each, in ticks of this sequence. */
    fun addFrames(frames: List<Frame>, fps: Int) = insertAll(
        frames.map { f -> SeqItem(CanvasSource(f.doc), (f.hold * rateHz.toDouble() / fps.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)) }
    )

    fun setTicks(ticks: Int) {
        val item = selectedItem ?: return
        if (item.ticks == ticks) return
        checkpoint("ticks$selected")
        items[selected] = item.copy(ticks = ticks.coerceAtLeast(1))
    }

    fun changeShape(s: FrameShape) {
        if (s == shape) return
        checkpoint()
        shape = s
    }

    /** How the selected item's picture sits in the frame. */
    fun setFit(fit: FrameFit) {
        val item = selectedItem ?: return
        checkpoint()
        items[selected] = item.copy(fit = fit)
    }

    /** Turns the selected item's picture a quarter clockwise. */
    fun turn() {
        val item = selectedItem ?: return
        checkpoint()
        items[selected] = item.copy(quarterTurns = (item.quarterTurns + 1) % 4)
    }

    /** Gives every item the selected item's fit and turn. */
    fun fitForAll() {
        val item = selectedItem ?: return
        checkpoint()
        for (i in items.indices) items[i] = items[i].copy(fit = item.effectiveFit, quarterTurns = item.quarterTurns)
    }

    fun setAllTicks(ticks: Int) {
        if (items.isEmpty()) return
        checkpoint()
        for (i in items.indices) items[i] = items[i].copy(ticks = ticks.coerceAtLeast(1))
    }

    /** Changes the rate and rescales every duration so the timing in seconds stays the same. */
    fun changeRate(hz: Int) {
        if (hz == rateHz) return
        checkpoint()
        for (i in items.indices) {
            items[i] = items[i].copy(ticks = (items[i].ticks * hz.toDouble() / rateHz).roundToInt().coerceAtLeast(1))
        }
        blackTicks = if (blackTicks == 0) 0 else (blackTicks * hz.toDouble() / rateHz).roundToInt().coerceAtLeast(1)
        rateHz = hz
    }

    fun duplicate() {
        val item = selectedItem ?: return
        checkpoint()
        items.add(selected + 1, item)
        selected++
    }

    fun delete() {
        if (selectedItem == null) return
        checkpoint()
        items.removeAt(selected)
        selected = selected.coerceAtMost((items.size - 1).coerceAtLeast(0))
    }

    fun move(delta: Int) {
        val to = selected + delta
        if (selectedItem == null || to !in items.indices) return
        checkpoint()
        items.add(to, items.removeAt(selected))
        selected = to
    }

    fun clear() {
        if (items.isEmpty()) return
        checkpoint()
        items.clear()
        selected = 0
    }

    companion object {
        private const val MAX_UNDO = 50
    }
}
