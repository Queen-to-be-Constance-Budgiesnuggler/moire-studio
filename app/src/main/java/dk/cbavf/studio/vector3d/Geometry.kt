package dk.cbavf.studio.vector3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point or direction in the 3D room. y is up; the space is right-handed. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** Each axis multiplied by the same axis of [o]. */
    fun scaledBy(o: Vec3) = Vec3(x * o.x, y * o.y, z * o.z)
    val length: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { if (it < 1e-12) ZERO else this * (1 / it) }

    /** Turned by [deg] about the x axis (tilt). */
    fun rotatedX(deg: Double): Vec3 {
        val a = deg * PI / 180
        return Vec3(x, y * cos(a) - z * sin(a), y * sin(a) + z * cos(a))
    }

    /** Turned by [deg] about the y axis (turn). */
    fun rotatedY(deg: Double): Vec3 {
        val a = deg * PI / 180
        return Vec3(x * cos(a) + z * sin(a), y, -x * sin(a) + z * cos(a))
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * A low-poly shape: flat faces given as vertex indices, counter-clockwise seen from outside so each face's normal points out.
 * Every face is convex. A [twoSided] mesh (a plane) is seen from both sides and never culled.
 */
class Mesh(val vertices: List<Vec3>, val faces: List<IntArray>, val twoSided: Boolean = false) {
    fun polygon(face: Int): List<Vec3> = faces[face].map { vertices[it] }

    /** The face's outward normal, by Newell's method (stable for any planar polygon). */
    fun normal(face: Int): Vec3 = Polygons.normal(polygon(face))

    fun centroid(face: Int): Vec3 = Polygons.centroid(polygon(face))

    fun map(f: (Vec3) -> Vec3) = Mesh(vertices.map(f), faces, twoSided)
}

/** Sums over flat convex polygons in 3D. */
object Polygons {
    fun normal(p: List<Vec3>): Vec3 {
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        return Vec3(nx, ny, nz).normalized()
    }

    fun centroid(p: List<Vec3>): Vec3 = p.fold(Vec3.ZERO) { s, v -> s + v } * (1.0 / p.size)
}

/** The shapes the 3D room can place, each one unit across and centred on the origin. */
object Primitives {
    private const val H = 0.5

    /** A cube from -0.5 to 0.5 on every axis, six square faces. */
    fun cube(): Mesh {
        val verts = ArrayList<Vec3>()
        val faces = ArrayList<IntArray>()
        // For each side: its normal n and two edges u, v with u × v = n, so (−u−v, +u−v, +u+v, −u+v) runs counter-clockwise.
        val sides = listOf(
            Triple(Vec3.X, Vec3.Y, Vec3.Z), Triple(-Vec3.X, Vec3.Z, Vec3.Y),
            Triple(Vec3.Y, Vec3.Z, Vec3.X), Triple(-Vec3.Y, Vec3.X, Vec3.Z),
            Triple(Vec3.Z, Vec3.X, Vec3.Y), Triple(-Vec3.Z, Vec3.Y, Vec3.X),
        )
        for ((n, u, v) in sides) {
            val c = n * H
            val start = verts.size
            verts += c - u * H - v * H
            verts += c + u * H - v * H
            verts += c + u * H + v * H
            verts += c - u * H + v * H
            faces += intArrayOf(start, start + 1, start + 2, start + 3)
        }
        return Mesh(verts, faces)
    }

    /** A flat square in the x–z plane, one unit across, facing up and seen from both sides. */
    fun plane(): Mesh = Mesh(
        listOf(Vec3(-H, 0.0, -H), Vec3(-H, 0.0, H), Vec3(H, 0.0, H), Vec3(H, 0.0, -H)),
        listOf(intArrayOf(0, 1, 2, 3)),
        twoSided = true,
    )

    /** A ball of triangles, radius 0.5: an icosahedron split [subdivisions] times (20, 80, 320 faces). */
    fun sphere(subdivisions: Int): Mesh {
        val t = (1 + sqrt(5.0)) / 2
        val verts = arrayListOf(
            Vec3(-1.0, t, 0.0), Vec3(1.0, t, 0.0), Vec3(-1.0, -t, 0.0), Vec3(1.0, -t, 0.0),
            Vec3(0.0, -1.0, t), Vec3(0.0, 1.0, t), Vec3(0.0, -1.0, -t), Vec3(0.0, 1.0, -t),
            Vec3(t, 0.0, -1.0), Vec3(t, 0.0, 1.0), Vec3(-t, 0.0, -1.0), Vec3(-t, 0.0, 1.0),
        ).map { it.normalized() * H }.toMutableList()
        var faces = listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )
        repeat(subdivisions.coerceIn(0, MAX_SUBDIVISIONS)) {
            val mid = HashMap<Long, Int>()
            fun midpoint(a: Int, b: Int): Int {
                val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
                return mid.getOrPut(key) { verts += ((verts[a] + verts[b]) * 0.5).normalized() * H; verts.size - 1 }
            }
            faces = faces.flatMap { (a, b, c) ->
                val ab = midpoint(a, b)
                val bc = midpoint(b, c)
                val ca = midpoint(c, a)
                listOf(intArrayOf(a, ab, ca), intArrayOf(b, bc, ab), intArrayOf(c, ca, bc), intArrayOf(ab, bc, ca))
            }
        }
        return outward(Mesh(verts, faces))
    }

    /**
     * An upright [sides]-sided column of height 1 and radius 0.5 (a low-poly cylinder); with [topRadius] 0 it closes to a
     * point (a cone or pyramid).
     */
    fun column(sides: Int, topRadius: Double = H): Mesh {
        val n = sides.coerceIn(3, MAX_SIDES)
        val verts = ArrayList<Vec3>()
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            verts += Vec3(H * cos(a), -H, H * sin(a))
        }
        val faces = ArrayList<IntArray>()
        faces += IntArray(n) { it }
        if (topRadius <= 1e-9) {
            verts += Vec3(0.0, H, 0.0)
            for (i in 0 until n) faces += intArrayOf(i, (i + 1) % n, n)
        } else {
            for (i in 0 until n) {
                val a = 2 * PI * i / n
                verts += Vec3(topRadius * cos(a), H, topRadius * sin(a))
            }
            faces += IntArray(n) { n + it }
            for (i in 0 until n) faces += intArrayOf(i, (i + 1) % n, n + (i + 1) % n, n + i)
        }
        return outward(Mesh(verts, faces))
    }

    /** Turns every face of a shape that is convex around the origin so that its normal points away from the origin. */
    fun outward(m: Mesh): Mesh = Mesh(m.vertices, m.faces.indices.map { f ->
        if (m.normal(f) dot m.centroid(f) < 0) m.faces[f].reversedArray() else m.faces[f]
    }, m.twoSided)

    const val MAX_SUBDIVISIONS = 2
    const val MAX_SIDES = 32
}
