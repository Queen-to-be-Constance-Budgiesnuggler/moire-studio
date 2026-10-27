package dk.cbavf.studio.vector3d

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** The shapes the room offers. [detail] is 0 to 3: more faces on the sphere and the columns. */
enum class Shape(val label: String) {
    Cube("Cube"), Sphere("Sphere"), Plane("Plane"), Cylinder("Cylinder"), Cone("Cone");

    fun mesh(detail: Int): Mesh {
        val d = detail.coerceIn(0, MAX_DETAIL)
        return when (this) {
            Cube -> Primitives.cube()
            Plane -> Primitives.plane()
            Sphere -> Primitives.sphere(minOf(d, Primitives.MAX_SUBDIVISIONS))
            Cylinder -> Primitives.column(COLUMN_SIDES[d])
            Cone -> Primitives.column(COLUMN_SIDES[d], topRadius = 0.0)
        }
    }

    companion object {
        const val MAX_DETAIL = 3
        private val COLUMN_SIDES = intArrayOf(4, 6, 10, 16)
    }
}

/**
 * Parallel lines drawn on every face of a shape. [spacing] and [width] are in room units, so lines get finer with distance;
 * [angleDeg] turns them within each face; [phase] (0 to 1 of a spacing) slides them across.
 */
data class Grating(
    val on: Boolean = true,
    val spacing: Double = 0.06,
    val angleDeg: Double = 0.0,
    val width: Double = 0.02,
    val phase: Double = 0.0,
    val color: Int = 0xFF000000.toInt(),
)

/** One shape in the room: where it is, its size per axis, how it is turned, its colour and its grating. */
data class SceneObject(
    val shape: Shape,
    val position: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3.ONE,
    val yawDeg: Double = 0.0,
    val tiltDeg: Double = 0.0,
    val color: Int = 0xFFE0E0E0.toInt(),
    /** Below 1 the faces are see-through, the back faces are drawn too, and gratings show through each other. */
    val opacity: Double = 1.0,
    val detail: Int = 1,
    val grating: Grating = Grating(),
) {
    /** A point of the scaled shape placed in the room: tilted, turned, then moved. */
    fun place(p: Vec3): Vec3 = p.rotatedX(tiltDeg).rotatedY(yawDeg) + position

    /** A direction of the scaled shape in the room (no move). */
    fun turn(d: Vec3): Vec3 = d.rotatedX(tiltDeg).rotatedY(yawDeg)
}

/** One light far away: [azimuthDeg] around, [elevationDeg] up; [ambient] is the light every face gets. */
data class Light(val azimuthDeg: Double = 40.0, val elevationDeg: Double = 55.0, val ambient: Double = 0.35) {
    /** The direction towards the light. */
    val direction: Vec3
        get() {
            val a = azimuthDeg * PI / 180
            val e = elevationDeg * PI / 180
            return Vec3(cos(e) * sin(a), sin(e), cos(e) * cos(a))
        }

    /** Flat shading: how bright a face with this outward normal is, from [ambient] to 1. */
    fun shade(normal: Vec3): Double = ambient + (1 - ambient) * max(0.0, normal.normalized() dot direction)
}

data class Scene(
    val objects: List<SceneObject>,
    val light: Light = Light(),
    val background: Int = 0xFFFFFFFF.toInt(),
) {
    /** A new [shape] at the centre, in the next colour of [PALETTE], at the end of the list. */
    fun add(shape: Shape): Scene = copy(objects = objects + SceneObject(shape, color = PALETTE[objects.size % PALETTE.size]))

    fun remove(index: Int): Scene = if (index !in objects.indices) this else copy(objects = objects.filterIndexed { i, _ -> i != index })

    fun update(index: Int, f: (SceneObject) -> SceneObject): Scene =
        if (index !in objects.indices) this else copy(objects = objects.mapIndexed { i, o -> if (i == index) f(o) else o })

    companion object {
        /** Colours given to new shapes in turn. */
        val PALETTE = intArrayOf(0xFFE0E0E0.toInt(), 0xFFF2C98A.toInt(), 0xFF9CC9E8.toInt(), 0xFFB8DDA0.toInt(), 0xFFE8A3B8.toInt(), 0xFFC7B3E6.toInt())

        /** What the room opens with: a striped floor and a striped cube turned against it. */
        val DEFAULT = Scene(listOf(
            SceneObject(Shape.Plane, Vec3(0.0, -0.5, 0.0), Vec3(4.0, 1.0, 4.0), grating = Grating(spacing = 0.08, width = 0.025)),
            SceneObject(Shape.Cube, yawDeg = 20.0, color = 0xFFF2C98A.toInt(), grating = Grating(spacing = 0.05, angleDeg = 30.0, width = 0.018)),
        ))
    }
}

/** A face ready to draw: its outline in pixels, its shaded fill, and its grating as line segments in pixels. */
class FaceDraw(
    /** x, y pairs. */
    val outline: FloatArray,
    val fill: Int,
    val depth: Double,
    /** x1, y1, x2, y2 for each line. */
    val lines: FloatArray,
    val lineColor: Int,
    val lineWidth: Float,
)

/** A whole picture: faces from the farthest to the nearest, so drawing them in order hides what is behind. */
class Frame(val width: Int, val height: Int, val background: Int, val faces: List<FaceDraw>)

/** Lines of a [Grating] on one flat convex face. */
object FaceGrating {
    /** No face gets more lines than this, however fine the spacing. */
    const val MAX_LINES = 400

    /**
     * The grating's lines on [polygon] (a flat convex face with outward [normal]), as pairs of end points. The lines run
     * along a direction set in the face from a fixed reference: across the up axis for walls, along x for floors.
     */
    fun segments(polygon: List<Vec3>, normal: Vec3, g: Grating): List<Pair<Vec3, Vec3>> {
        if (!g.on || g.spacing <= 0 || polygon.size < 3) return emptyList()
        val n = normal.normalized()
        val ref = if (kotlin.math.abs(n.y) > 0.99) Vec3.Z else Vec3.Y
        val u = (ref cross n).normalized()
        val v = n cross u
        val a = g.angleDeg * PI / 180
        val along = u * cos(a) + v * sin(a)
        val across = n cross along
        val levels = polygon.map { it dot across }
        val offset = g.phase * g.spacing
        val first = ceil((levels.min() - offset) / g.spacing).toLong()
        val last = floor((levels.max() - offset) / g.spacing).toLong()
        if (last < first) return emptyList()
        val step = max(1L, (last - first + 1 + MAX_LINES - 1) / MAX_LINES)
        val out = ArrayList<Pair<Vec3, Vec3>>()
        var k = first
        while (k <= last) {
            cut(polygon, levels, offset + k * g.spacing)?.let { out += it }
            k += step
        }
        return out
    }

    /**
     * Where the level line `p · across = c` crosses a convex polygon whose vertices have [levels]; null if it misses it or
     * only runs along an edge or touches a corner, so a face's border never gets a line of its own.
     */
    fun cut(polygon: List<Vec3>, levels: List<Double>, c: Double): Pair<Vec3, Vec3>? {
        if (c <= levels.min() + EDGE || c >= levels.max() - EDGE) return null
        val hits = ArrayList<Vec3>(2)
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val fi = levels[i] - c
            val fj = levels[j] - c
            if ((fi < 0) != (fj < 0)) {
                val t = fi / (fi - fj)
                hits += polygon[i] + (polygon[j] - polygon[i]) * t
            }
        }
        return if (hits.size >= 2 && (hits[1] - hits[0]).length > EDGE) hits[0] to hits[1] else null
    }

    private const val EDGE = 1e-9
}

/** Turns a [Scene] seen by an [OrbitCamera] into a [Frame]: culling, flat shading, gratings and depth order. */
object Render3d {
    fun frame(scene: Scene, camera: OrbitCamera, width: Int, height: Int): Frame {
        val w = width.toDouble()
        val h = height.toDouble()
        val focal = camera.focal(w, h)
        val out = ArrayList<FaceDraw>()
        for (obj in scene.objects) {
            // Scaled first, so the grating's spacing is in room units; then turned and moved as a whole.
            val local = obj.shape.mesh(obj.detail).map { it.scaledBy(obj.scale) }
            val seeThrough = obj.opacity < 1.0
            for (f in local.faces.indices) {
                val poly = local.polygon(f)
                val localNormal = Polygons.normal(poly)
                val world = poly.map(obj::place)
                val centre = Polygons.centroid(world)
                var normal = obj.turn(localNormal)
                val front = camera.faces(centre, normal)
                if (!front) {
                    if (!local.twoSided && !seeThrough) continue
                    normal = -normal
                }
                val pts = world.map { camera.project(it, w, h) }
                if (pts.any { it == null }) continue
                val outline = FloatArray(pts.size * 2)
                pts.forEachIndexed { i, p -> outline[2 * i] = p!!.x.toFloat(); outline[2 * i + 1] = p.y.toFloat() }
                val segs = FaceGrating.segments(poly, localNormal, obj.grating)
                val lines = FloatArray(segs.size * 4)
                var n = 0
                for ((a, b) in segs) {
                    val pa = camera.project(obj.place(a), w, h) ?: continue
                    val pb = camera.project(obj.place(b), w, h) ?: continue
                    lines[n++] = pa.x.toFloat(); lines[n++] = pa.y.toFloat(); lines[n++] = pb.x.toFloat(); lines[n++] = pb.y.toFloat()
                }
                val depth = camera.toView(centre).z
                out += FaceDraw(
                    outline,
                    Colors.shaded(obj.color, scene.light.shade(normal), obj.opacity),
                    depth,
                    if (n == lines.size) lines else lines.copyOf(n),
                    obj.grating.color,
                    (obj.grating.width * focal / depth).toFloat(),
                )
            }
        }
        out.sortByDescending { it.depth }
        return Frame(width, height, scene.background, out)
    }
}

/** ARGB colour sums. */
object Colors {
    /** [argb] with its red, green and blue times [shade] (0 to 1), and its alpha set to [opacity]. */
    fun shaded(argb: Int, shade: Double, opacity: Double = 1.0): Int {
        val s = shade.coerceIn(0.0, 1.0)
        val r = ((argb shr 16 and 0xFF) * s).toInt()
        val g = ((argb shr 8 and 0xFF) * s).toInt()
        val b = ((argb and 0xFF) * s).toInt()
        val a = (opacity.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
