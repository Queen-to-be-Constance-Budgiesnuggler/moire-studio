package dk.cbavf.studio.animate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.animate.model.Animation
import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.animate.model.Frame
import dk.cbavf.studio.animate.model.Step
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.ownPivot
import dk.cbavf.studio.draw.model.transformed
import dk.cbavf.studio.library.CanvasDoc

enum class AnimateView { Frames, Barrier }

/**
 * Stop-motion state for the Animate room. Frames are full canvas recipes, so any frame can go
 * back to Draw and every output can be re-rendered exactly. Edits replace frames, which keeps
 * undo a list of snapshots, as in Draw.
 */
class AnimateState {
    val frames = mutableStateListOf<Frame>()

    var current by mutableIntStateOf(0)
    var fps by mutableIntStateOf(12)
    var stripPx by mutableIntStateOf(3)

    var view by mutableStateOf(AnimateView.Frames)
    var playing by mutableStateOf(false)
    var onion by mutableStateOf(true)

    /** While on, a drag on the canvas adds a frame every 1/fps (capture-while-dragging). */
    var recording by mutableStateOf(false)

    /** Layer that drags and the step helper move; defaults to the top layer. */
    var targetId by mutableStateOf<Int?>(null)

    var step by mutableStateOf(Step(dx = 1f))
    var stepCount by mutableIntStateOf(8)

    /** Barrier view: how many strips the slit overlay is slid, i.e. which frame shows. */
    var barrierShift by mutableIntStateOf(0)

    var canUndo by mutableStateOf(false)
        private set

    /** Why the barrier preview could not be made, if it failed. */
    var barrierError by mutableStateOf<String?>(null)

    private val history = ArrayDeque<AnimationDoc>()
    private var lastCaptureMs = 0L

    val currentFrame: Frame? get() = frames.getOrNull(current)
    val previousFrame: Frame? get() = frames.getOrNull(current - 1)

    /** The target layer if it exists in the current frame, else the frame's top layer. */
    val target: Layer?
        get() {
            val layers = currentFrame?.doc?.layers ?: return null
            return layers.firstOrNull { it.id == targetId } ?: layers.lastOrNull()
        }

    fun toDoc() = AnimationDoc(frames.toList(), fps, stripPx)

    fun load(doc: AnimationDoc) {
        frames.clear()
        frames.addAll(doc.frames)
        fps = doc.fps
        stripPx = doc.stripPx
        current = current.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
        history.clear()
        canUndo = false
    }

    /** Opens an animation as an undoable edit (Library → Open in Animate). */
    fun open(doc: AnimationDoc) {
        checkpoint()
        frames.clear()
        frames.addAll(doc.frames)
        fps = doc.fps
        stripPx = doc.stripPx
        current = 0
    }

    // --- Undo -----------------------------------------------------------------------------

    fun checkpoint() {
        history.addLast(toDoc())
        if (history.size > MAX_UNDO) history.removeFirst()
        canUndo = true
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        frames.clear()
        frames.addAll(previous.frames)
        fps = previous.fps
        stripPx = previous.stripPx
        current = current.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
        canUndo = history.isNotEmpty()
    }

    // --- Frames ---------------------------------------------------------------------------

    /** Adds a canvas as a new frame after the current one and shows it. */
    fun insert(doc: CanvasDoc) {
        checkpoint()
        val at = if (frames.isEmpty()) 0 else current + 1
        frames.add(at, Frame(doc))
        current = at
    }

    /**
     * Each Library image becomes a frame, fitted to the animation's canvas ([fallback] size when
     * there are no frames yet). Returns false when no canvas size is known.
     */
    fun addImageFrames(images: List<dk.cbavf.studio.library.AssetRecord>, fallback: Pair<Int, Int>?): Boolean {
        val (w, h) = frames.firstOrNull()?.doc?.let { it.widthPx to it.heightPx } ?: fallback ?: return false
        insertAll(images.map { a ->
            CanvasDoc(w, h, listOf(dk.cbavf.studio.draw.model.Layer(1, dk.cbavf.studio.draw.model.ImageLayer.fitted(a.id, a.widthPx, a.heightPx, w, h, 1f))))
        })
        return true
    }

    /** Adds several frames after the current one (one undo step). */
    fun insertAll(docs: List<CanvasDoc>) {
        if (docs.isEmpty()) return
        checkpoint()
        val at = if (frames.isEmpty()) 0 else current + 1
        frames.addAll(at, docs.map { Frame(it) })
        current = at + docs.size - 1
    }

    fun replaceCurrent(doc: CanvasDoc) {
        val f = currentFrame ?: return
        checkpoint()
        frames[current] = f.copy(doc = doc)
    }

    fun duplicateCurrent() {
        val f = currentFrame ?: return
        checkpoint()
        frames.add(current + 1, f)
        current++
    }

    /** Removes every frame (one undo step brings them all back). */
    fun clearAll() {
        if (frames.isEmpty()) return
        playing = false
        checkpoint()
        frames.clear()
        current = 0
    }

    fun deleteCurrent() {
        if (currentFrame == null) return
        checkpoint()
        frames.removeAt(current)
        current = current.coerceAtMost((frames.size - 1).coerceAtLeast(0))
    }

    /** Moves the current frame one place earlier (−1) or later (+1). */
    fun moveCurrent(delta: Int) {
        val to = current + delta
        if (currentFrame == null || to !in frames.indices) return
        checkpoint()
        val f = frames.removeAt(current)
        frames.add(to, f)
        current = to
    }

    fun changeHold(delta: Int) {
        val f = currentFrame ?: return
        val hold = (f.hold + delta).coerceIn(1, MAX_HOLD)
        if (hold == f.hold) return
        checkpoint()
        frames[current] = f.copy(hold = hold)
    }

    /**
     * Appends a recorded frame (Patch bay recording). Only the first frame of a take adds an undo
     * step, so one Undo removes the whole take.
     */
    fun record(doc: CanvasDoc, firstOfTake: Boolean) {
        if (firstOfTake) checkpoint()
        frames.add(doc.let { Frame(it) })
        current = frames.lastIndex
    }

    /** Step helper: appends [stepCount] frames after the current one, each moved by [step]. */
    fun generateSteps() {
        val f = currentFrame ?: return
        val id = target?.id ?: return
        checkpoint()
        val generated = Animation.stepFrames(f.doc, id, step, stepCount)
        frames.addAll(current + 1, generated)
        current += generated.size
    }

    // --- Drag: edit the current frame, or record frames while dragging --------------------

    fun beginDrag(nowMs: Long) {
        if (currentFrame == null) return
        checkpoint()
        if (recording) {
            // The frame being dragged is always a fresh copy; the one before it stays as it was.
            frames.add(current + 1, frames[current])
            current++
            lastCaptureMs = nowMs
        }
    }

    fun drag(pan: Pt, zoom: Float, rotationDeg: Float, nowMs: Long) {
        val f = currentFrame ?: return
        val id = target?.id ?: return
        val doc = f.doc.copy(
            layers = f.doc.layers.map { layer ->
                if (layer.id != id) layer
                else layer.copy(content = layer.content.transformed(pan, zoom, rotationDeg, layer.content.ownPivot))
            }
        )
        frames[current] = f.copy(doc = doc)
        if (recording && nowMs - lastCaptureMs >= 1000L / fps.coerceAtLeast(1)) {
            // Freeze this pose as a frame and keep dragging a copy of it.
            frames.add(current + 1, frames[current])
            current++
            lastCaptureMs = nowMs
        }
    }

    companion object {
        const val MAX_HOLD = 24
        private const val MAX_UNDO = 50
    }
}
