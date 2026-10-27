package dk.cbavf.studio.animate.model

import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.MoireMath
import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.draw.model.ownPivot
import dk.cbavf.studio.draw.model.transformed
import dk.cbavf.studio.library.CanvasDoc
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One stop-motion frame: a full canvas recipe shown for [hold] playback ticks. */
@Serializable
data class Frame(val doc: CanvasDoc, val hold: Int = 1)

/**
 * A moiré-shift animation: keyframes plus playback and barrier-grid settings.
 * Also the recipe stored with Animate outputs in the Library.
 */
@Serializable
data class AnimationDoc(
    val frames: List<Frame> = emptyList(),
    val fps: Int = 12,
    /** Barrier grid: width of one frame's strip in the interleaved image. */
    val stripPx: Int = 3,
) {
    /** Frame index per playback tick, with holds expanded. */
    fun playbackOrder(): List<Int> = frames.flatMapIndexed { i, f -> List(f.hold.coerceAtLeast(1)) { i } }

    val slitSpacingPx: Int get() = MoireMath.barrierSlitSpacing(frames.size, stripPx.toDouble()).toInt()
}

/** Per-frame motion for the step helper: applied once per generated frame to one layer. */
data class Step(val dx: Float = 0f, val dy: Float = 0f, val turnDeg: Float = 0f)

object Animation {
    /** Applies [step] to layer [layerId]; other layers are untouched. */
    fun stepped(doc: CanvasDoc, layerId: Int, step: Step): CanvasDoc = doc.copy(
        layers = doc.layers.map { layer ->
            if (layer.id != layerId) layer
            else layer.copy(content = layer.content.transformed(Pt(step.dx, step.dy), 1f, step.turnDeg, layer.content.ownPivot))
        }
    )

    /** [count] new frames after [from], each one [step] further than the last. */
    fun stepFrames(from: CanvasDoc, layerId: Int, step: Step, count: Int): List<Frame> {
        var doc = from
        return List(count) {
            doc = stepped(doc, layerId, step)
            Frame(doc)
        }
    }

    /**
     * How far a grating's lines move per frame (along their normal), and the largest shift that
     * still reads as motion in the intended direction (p/2). Null for other layer types.
     */
    fun aliasingCheck(doc: CanvasDoc, layerId: Int, step: Step): Pair<Double, Double>? {
        val g = doc.layers.firstOrNull { it.id == layerId }?.content as? Grating ?: return null
        val a = (g.angleDeg + step.turnDeg) * PI / 180
        val along = abs(step.dx * -sin(a) + step.dy * cos(a))
        return along to MoireMath.aliasingLimit(g.spacingPx.toDouble())
    }

    /** Which frame a column of the interleaved image shows. */
    fun interleaveFrame(x: Int, stripPx: Int, frames: Int): Int = Math.floorDiv(x, stripPx).mod(frames)

    /** True where the barrier is transparent; [shiftStrips] slides it, one strip per frame. */
    fun barrierOpen(x: Int, stripPx: Int, frames: Int, shiftStrips: Int): Boolean =
        interleaveFrame(x, stripPx, frames) == shiftStrips.mod(frames)
}
