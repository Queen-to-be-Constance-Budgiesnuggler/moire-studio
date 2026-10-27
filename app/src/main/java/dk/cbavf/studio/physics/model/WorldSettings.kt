package dk.cbavf.studio.physics.model

import dk.cbavf.studio.draw.model.Pt
import kotlin.math.sqrt

/** How the world steps and what its bodies are made of. Lengths on the canvas are pixels; the world works in metres. */
data class WorldSettings(
    /** Canvas pixels per metre: a 1080 px wide canvas is about 11 m across. */
    val pxPerMetre: Float = 100f,
    /** Frames per second of simulated time: one step per frame. */
    val fps: Int = 30,
    /** Smaller steps inside each frame: fewer bodies pass through each other. */
    val substeps: Int = 4,
    /** 0 = no bounce, 1 = bounces back as high. */
    val bounce: Float = 0.4f,
    val friction: Float = 0.3f,
    val density: Float = 1f,
    /** Walls round the canvas edges keep the bodies on it. */
    val walls: Boolean = true,
    /** Gravity as a share of the Earth's (or of the tilt). */
    val gravityScale: Float = 1f,
    /** Hits on the walls count as triggers. */
    val wallsTrigger: Boolean = true,
) {
    val stepSeconds: Double get() = 1.0 / fps.coerceIn(MIN_FPS, MAX_FPS)
    val substepSeconds: Double get() = stepSeconds / substeps.coerceIn(1, MAX_SUBSTEPS)

    fun metres(px: Float): Double = px.toDouble() / pxPerMetre
    fun pixels(m: Double): Float = (m * pxPerMetre).toFloat()

    /** Every value inside its range (a loaded or typed setting can be anything). */
    fun clamped() = copy(
        pxPerMetre = pxPerMetre.coerceIn(10f, 1000f),
        fps = fps.coerceIn(MIN_FPS, MAX_FPS),
        substeps = substeps.coerceIn(1, MAX_SUBSTEPS),
        bounce = bounce.coerceIn(0f, 1f),
        friction = friction.coerceIn(0f, 1f),
        density = density.coerceIn(0.05f, 20f),
        gravityScale = gravityScale.coerceIn(0f, 3f),
    )

    companion object {
        const val MIN_FPS = 6
        const val MAX_FPS = 60
        const val MAX_SUBSTEPS = 16
    }
}

/** Where gravity comes from: the phone's tilt, or straight down the screen. */
enum class GravitySource(val label: String) { Tilt("Tilt"), Down("Down") }

/** Gravity on the canvas, in m/s², with y pointing down the screen as on the canvas. */
object Gravity {
    const val EARTH = 9.81f

    /** Below this the accelerometer has not sent anything yet (it reads near 9.81 even lying still). */
    const val NO_READING = 1f

    val down = Pt(0f, EARTH)

    /**
     * Gravity on the screen from the accelerometer ([ax], [ay], [az], the phone's own axes in m/s²) and how far the screen is turned
     * ([rotation]: quarter turns, as Display.getRotation gives them). The accelerometer reads the push that holds the phone up,
     * so gravity is its opposite; the phone's y points up the screen, the canvas's down. Lying flat, the tilt is near zero and the
     * bodies stay where they are. Null when there is no reading yet.
     */
    fun fromAccel(ax: Float, ay: Float, az: Float, rotation: Int = 0, scale: Float = 1f): Pt? {
        if (sqrt(ax * ax + ay * ay + az * az) < NO_READING) return null
        // The reading in the screen's axes (x right, y up), for the way the screen is turned.
        val (sx, sy) = when (rotation.mod(4)) {
            1 -> -ay to ax
            2 -> -ax to -ay
            3 -> ay to -ax
            else -> ax to ay
        }
        return Pt(-sx * scale, sy * scale)
    }

    /** The gravity to use: the tilt when asked for and there is a reading, otherwise straight down. */
    fun pick(source: GravitySource, reading: Pt?, scale: Float): Pt =
        if (source == GravitySource.Tilt && reading != null) reading else down * scale
}
