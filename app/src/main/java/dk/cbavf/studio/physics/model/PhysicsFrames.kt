package dk.cbavf.studio.physics.model

import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.draw.model.transformed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Where a body is after some steps: its centre on the canvas (pixels) and how far it is turned. */
data class Pose(val x: Float, val y: Float, val angleDeg: Float)

/** A static rectangle (a wall), in canvas pixels. */
data class WallRect(val centre: Pt, val width: Float, val height: Float)

object PhysicsFrames {
    /**
     * The canvas with each body's layer moved to its pose: turned round the body's first centre by how far it has turned, then
     * moved by how far its centre has gone. Layers that are not moving bodies stay as they were, so a frame is still a full canvas
     * recipe Animate can show, step and send back to Draw.
     */
    fun frame(doc: CanvasDoc, specs: List<BodySpec>, poses: Map<Int, Pose>): CanvasDoc {
        val byId = specs.associateBy { it.layerId }
        return doc.copy(layers = doc.layers.map { layer ->
            val spec = byId[layer.id]?.takeIf { it.kind == BodyKind.Moving } ?: return@map layer
            val pose = poses[layer.id] ?: return@map layer
            val pan = Pt(pose.x - spec.centre.x, pose.y - spec.centre.y)
            layer.copy(content = layer.content.transformed(pan, 1f, pose.angleDeg - spec.angleDeg, spec.centre))
        })
    }

    /** The four walls just outside a canvas, [thickPx] thick, overlapping at the corners so nothing slips out between them. */
    fun walls(widthPx: Int, heightPx: Int, thickPx: Float): List<WallRect> {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val t = thickPx
        return listOf(
            WallRect(Pt(w / 2, h + t / 2), w + 2 * t, t),   // floor
            WallRect(Pt(w / 2, -t / 2), w + 2 * t, t),      // ceiling
            WallRect(Pt(-t / 2, h / 2), t, h + 2 * t),      // left
            WallRect(Pt(w + t / 2, h / 2), t, h + 2 * t),   // right
        )
    }

    /** A body's outline on the canvas at [pose], for drawing it over the frame. A circle is drawn with [circleSides] sides. */
    fun outline(shape: BodyShape, pose: Pose, circleSides: Int = 24): List<Pt> {
        val local = when (shape) {
            is BodyShape.Circle -> List(circleSides) { i ->
                val a = 2 * PI * i / circleSides
                Pt((cos(a) * shape.radius).toFloat(), (sin(a) * shape.radius).toFloat())
            }
            is BodyShape.Box -> listOf(
                Pt(-shape.width / 2, -shape.height / 2), Pt(shape.width / 2, -shape.height / 2),
                Pt(shape.width / 2, shape.height / 2), Pt(-shape.width / 2, shape.height / 2),
            )
            is BodyShape.Polygon -> shape.points
            is BodyShape.Chain -> shape.points
        }
        val a = pose.angleDeg * PI.toFloat() / 180
        val c = cos(a)
        val s = sin(a)
        return local.map { p -> Pt(pose.x + p.x * c - p.y * s, pose.y + p.x * s + p.y * c) }
    }
}
