package dk.cbavf.studio.physics

import dk.cbavf.studio.draw.model.Pt
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.physics.model.BodyKind
import dk.cbavf.studio.physics.model.BodyShape
import dk.cbavf.studio.physics.model.BodySpec
import dk.cbavf.studio.physics.model.ContactEdges
import dk.cbavf.studio.physics.model.ContactPair
import dk.cbavf.studio.physics.model.Hit
import dk.cbavf.studio.physics.model.PhysicsFrames
import dk.cbavf.studio.physics.model.Pose
import dk.cbavf.studio.physics.model.TriggerOut
import dk.cbavf.studio.physics.model.WorldSettings
import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Convex
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World

/**
 * The bodies of one canvas in a dyn4j world (no Android in here). The canvas is in pixels with y down; the world is in metres with
 * the same axes, so gravity down the screen is +y. Each body's local origin is its [BodySpec.centre], so a body's place and turn
 * map straight back onto its layer ([PhysicsFrames.frame]). Not thread-safe: one coroutine steps it.
 */
class PhysicsWorld(
    val specs: List<BodySpec>,
    private val settings: WorldSettings,
    private val canvasW: Int,
    private val canvasH: Int,
) {
    private val world = World<Body>()
    private val bodies = LinkedHashMap<Int, Body>()
    private val edges = ContactEdges()

    /** Steps taken since the start. */
    var steps = 0
        private set

    init {
        world.setGravity(0.0, 0.0)
        // Bodies must not fall asleep: a tilt has to wake them.
        world.settings.isAtRestDetectionEnabled = false
        for (spec in specs) {
            if (spec.kind == BodyKind.Off) continue
            val body = bodyOf(spec) ?: continue
            bodies[spec.layerId] = body
            world.addBody(body)
        }
        if (settings.walls && canvasW > 0 && canvasH > 0) {
            PhysicsFrames.walls(canvasW, canvasH, WALL_PX).forEachIndexed { i, r ->
                val wall = Body()
                fixture(wall, Geometry.createRectangle(settings.metres(r.width), settings.metres(r.height)))
                wall.translate(settings.metres(r.centre.x), settings.metres(r.centre.y))
                wall.setMass(MassType.INFINITE)
                wall.userData = -(i + 1)
                world.addBody(wall)
            }
        }
    }

    /** One frame forward under [gravity] (m/s², canvas axes). Gives back the pairs that have just met and how hard. */
    fun step(gravity: Pt): List<Hit> {
        world.setGravity(gravity.x.toDouble(), gravity.y.toDouble())
        // Speeds before the step: after it a hit has already bounced.
        val before = world.bodies.associateWith { it.linearVelocity.copy() }
        world.step(settings.substeps.coerceIn(1, WorldSettings.MAX_SUBSTEPS), settings.substepSeconds)
        steps++
        val now = HashSet<ContactPair>()
        val pairBodies = HashMap<ContactPair, Pair<Body, Body>>()
        for (b in world.bodies) {
            val id = b.userData as? Int ?: continue
            for (o in world.getInContactBodies(b, false)) {
                val oid = o.userData as? Int ?: continue
                val p = ContactPair.of(id, oid)
                if (now.add(p)) pairBodies[p] = b to o
            }
        }
        return edges.step(now).map { p ->
            val (a, b) = pairBodies.getValue(p)
            val va = before[a] ?: a.linearVelocity
            val vb = before[b] ?: b.linearVelocity
            Hit(p, TriggerOut.strength(va.difference(vb).magnitude.toFloat()))
        }
    }

    /** Where every body in the world is now, by layer id. */
    fun poses(): Map<Int, Pose> = bodies.mapValues { (_, b) ->
        val t = b.transform
        Pose(settings.pixels(t.translationX), settings.pixels(t.translationY), Math.toDegrees(t.rotationAngle).toFloat())
    }

    /** [doc] with the moving bodies where they are now. */
    fun frame(doc: CanvasDoc): CanvasDoc = PhysicsFrames.frame(doc, specs, poses())

    private fun bodyOf(spec: BodySpec): Body? {
        val body = Body()
        val m = settings::metres
        when (val s = spec.shape) {
            is BodyShape.Circle -> fixture(body, Geometry.createCircle(m(s.radius)))
            is BodyShape.Box -> fixture(body, Geometry.createRectangle(m(s.width), m(s.height)))
            is BodyShape.Polygon -> {
                // An outline dyn4j will not take (too thin, points too close) falls back to its box.
                val polygon = runCatching { Geometry.createPolygon(*s.points.map { Vector2(m(it.x), m(it.y)) }.toTypedArray()) }.getOrNull()
                fixture(body, polygon ?: boxAround(s.points, m))
            }
            is BodyShape.Chain -> s.points.zipWithNext().forEach { (a, b) ->
                if (a != b) fixture(body, Geometry.createSegment(Vector2(m(a.x), m(a.y)), Vector2(m(b.x), m(b.y))))
            }
        }
        if (body.fixtures.isEmpty()) return null
        body.rotate(Math.toRadians(spec.angleDeg.toDouble()))
        body.translate(m(spec.centre.x), m(spec.centre.y))
        body.setMass(if (spec.kind == BodyKind.Moving && spec.shape !is BodyShape.Chain) MassType.NORMAL else MassType.INFINITE)
        // Small fast bodies would pass through thin lines between two steps.
        body.isBullet = spec.kind == BodyKind.Moving
        body.userData = spec.layerId
        return body
    }

    private fun boxAround(points: List<Pt>, m: (Float) -> Double): Convex {
        val w = (points.maxOf { it.x } - points.minOf { it.x }).coerceAtLeast(1f)
        val h = (points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1f)
        return Geometry.createRectangle(m(w), m(h))
    }

    private fun fixture(body: Body, convex: Convex): BodyFixture = body.addFixture(convex).apply {
        density = settings.density.toDouble()
        friction = settings.friction.toDouble()
        restitution = settings.bounce.toDouble()
    }

    companion object {
        /** How thick the walls round the canvas are, in pixels. */
        const val WALL_PX = 200f
    }
}
