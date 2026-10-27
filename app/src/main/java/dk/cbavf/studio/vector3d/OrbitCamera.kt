package dk.cbavf.studio.vector3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A point on the screen, in pixels from the top left, and how far in front of the camera it is. */
data class ScreenPoint(val x: Double, val y: Double, val depth: Double)

/**
 * A camera that circles [target]: [yawDeg] turns it around the up axis, [pitchDeg] lifts it, [distance] is how far away it
 * is. At yaw 0 and pitch 0 it sits on the +z side looking towards −z. Perspective, with [fovDeg] across the shorter side
 * of the screen.
 */
data class OrbitCamera(
    val yawDeg: Double = 35.0,
    val pitchDeg: Double = 25.0,
    val distance: Double = 4.5,
    val target: Vec3 = Vec3.ZERO,
    val fovDeg: Double = 50.0,
) {
    private val yaw = yawDeg * PI / 180
    private val pitch = pitchDeg.coerceIn(-MAX_PITCH, MAX_PITCH) * PI / 180

    val eye: Vec3 = target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance
    private val forward: Vec3 = (target - eye).normalized()
    private val right: Vec3 = (forward cross Vec3.Y).normalized()
    private val up: Vec3 = right cross forward

    /** Turned by a drag: yaw wraps around, pitch stops short of straight up or down. */
    fun orbit(dYawDeg: Double, dPitchDeg: Double) = copy(
        yawDeg = ((yawDeg + dYawDeg) % 360 + 360) % 360,
        pitchDeg = (pitchDeg + dPitchDeg).coerceIn(-MAX_PITCH, MAX_PITCH),
    )

    /** Closer when [factor] is above 1 (a pinch outwards), held between [MIN_DISTANCE] and [MAX_DISTANCE]. */
    fun zoom(factor: Double) = if (factor <= 0) this else copy(distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /** [p] in the camera's own axes: x to the right, y up, z the depth in front of the camera. */
    fun toView(p: Vec3): Vec3 {
        val d = p - eye
        return Vec3(d dot right, d dot up, d dot forward)
    }

    /** Pixels per unit at depth 1, for a [width] × [height] screen. */
    fun focal(width: Double, height: Double): Double = minOf(width, height) / 2 / tan(fovDeg * PI / 360)

    /** Where [p] lands on a [width] × [height] screen; null if it is behind the camera or too close to it. */
    fun project(p: Vec3, width: Double, height: Double): ScreenPoint? {
        val v = toView(p)
        if (v.z < NEAR) return null
        val f = focal(width, height)
        return ScreenPoint(width / 2 + v.x * f / v.z, height / 2 - v.y * f / v.z, v.z)
    }

    /** True when the side of a face with outward [normal] through [point] looks towards the camera. */
    fun faces(point: Vec3, normal: Vec3): Boolean = normal dot (eye - point) > 0

    companion object {
        const val MAX_PITCH = 89.0
        const val MIN_DISTANCE = 1.2
        const val MAX_DISTANCE = 30.0
        const val NEAR = 0.05
    }
}
