package dk.cbavf.studio.math

import kotlin.math.roundToInt

/**
 * One of the studio's curves as y = f(x), both 0..1, with the [MathParams] keys it reads. Each one calls the studio's own code
 * the way the studio calls it, so its plot shows what the studio does with the parameters as they are now.
 */
class MathFunction(
    val id: String,
    val title: String,
    val description: String,
    val params: List<String>,
    private val f: (Double) -> Double,
) {
    /** y for [x]; x is kept inside 0..1. */
    operator fun invoke(x: Double): Double = f(x.coerceIn(0.0, 1.0))
}

/** The functions the Math room lists. */
object MathFunctions {
    /** The sensor average is plotted over this many frames (one second at 60 frames a second). */
    const val SENSOR_FRAMES = 60

    /** Clock periods across the plot. */
    const val CLOCK_PERIODS = 4f

    private val clock = dk.cbavf.studio.routing.GateBank().apply { clockHz = CLOCK_PERIODS }

    private fun envelope(curve: Curve): (Double) -> Double = { x -> Envelope.shape(x, curve, MathParams.get("envelope.curveAmount")) }

    val all: List<MathFunction> = listOf(
        MathFunction("envelope.linear", "Linear", "The straight envelope curve: out is in.", emptyList(), envelope(Curve.Linear)),
        MathFunction(
            "envelope.exponential", "Exponential", "The Exponential envelope curve: starts slow, ends fast.",
            listOf("envelope.curveAmount"), envelope(Curve.Exponential),
        ),
        MathFunction(
            "envelope.logarithmic", "Logarithmic", "The Logarithmic envelope curve: starts fast, ends slow.",
            listOf("envelope.curveAmount"), envelope(Curve.Logarithmic),
        ),
        MathFunction("envelope.scurve", "S-curve", "The S-curve: slow at both ends, fast in the middle.", emptyList(), envelope(Curve.SCurve)),
        MathFunction(
            "gate.clock", "Clock gate", "The Studio clock's gate over four periods: on for the first part of each.",
            listOf("gate.duty"),
        ) { x -> if (clock.clockOn(x)) 1.0 else 0.0 },
        MathFunction(
            "sensor.slow", "Sensor average", "How a Sensor node's slow average follows a jump from 0 to 1, over one second.",
            listOf("sensor.slowAlpha"),
        ) { x -> sensorStep((x * SENSOR_FRAMES).roundToInt()) },
    )

    fun byId(id: String): MathFunction? = all.firstOrNull { it.id == id }

    /** A Sensor node's slow average [frames] frames after the sensor jumped from 0 to 1, updated the way the node does it. */
    fun sensorStep(frames: Int): Double {
        val alpha = MathParams.get("sensor.slowAlpha")
        var slow = 0.0
        repeat(frames.coerceAtLeast(0)) { slow += alpha * (1.0 - slow) }
        return slow
    }
}
