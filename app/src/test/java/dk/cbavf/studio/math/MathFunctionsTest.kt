package dk.cbavf.studio.math

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathFunctionsTest {
    @After fun clean() = MathParams.resetAll()

    @Test fun idsAreUniqueAndFoundById() {
        val ids = MathFunctions.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertNotNull(MathFunctions.byId(id))
        assertNull(MathFunctions.byId("nope"))
    }

    @Test fun everyListedParameterIsARealMathParam() {
        for (f in MathFunctions.all) for (k in f.params) assertNotNull("${f.id}: $k", MathParams.param(k))
    }

    @Test fun everyFunctionStaysInsideZeroToOne() {
        for (f in MathFunctions.all) for (i in 0..100) {
            val y = f(i / 100.0)
            assertTrue("${f.id} at $i: $y", y in 0.0..1.0)
        }
    }

    @Test fun xOutsideZeroToOneIsKeptAtTheEdge() {
        val f = MathFunctions.byId("envelope.exponential")!!
        assertEquals(f(0.0), f(-3.0), 0.0)
        assertEquals(f(1.0), f(5.0), 0.0)
    }

    @Test fun linearIsTheIdentity() {
        val f = MathFunctions.byId("envelope.linear")!!
        assertEquals(0.25, f(0.25), 1e-12)
        assertEquals(0.8, f(0.8), 1e-12)
    }

    @Test fun exponentialFollowsCurveAmount() {
        val f = MathFunctions.byId("envelope.exponential")!!
        val gentle = f(0.5)
        MathParams.set("envelope.curveAmount", 10.0)
        assertTrue(f(0.5) < gentle)
    }

    @Test fun logarithmicMirrorsExponential() {
        val e = MathFunctions.byId("envelope.exponential")!!
        val l = MathFunctions.byId("envelope.logarithmic")!!
        assertEquals(1.0 - e(0.7), l(0.3), 1e-12)
    }

    @Test fun clockGateIsOnForTheDutyShareOfEachPeriod() {
        val g = MathFunctions.byId("gate.clock")!!
        // Four periods across the plot: x = 0.1 is 40 % into a period, 0.2 is 80 %.
        assertEquals(1.0, g(0.1), 0.0)
        assertEquals(0.0, g(0.2), 0.0)
        MathParams.set("gate.duty", 0.9)
        assertEquals(1.0, g(0.2), 0.0)
    }

    @Test fun sensorAverageStartsAtZeroAndTakesOneAlphaStepPerFrame() {
        assertEquals(0.0, MathFunctions.sensorStep(0), 0.0)
        assertEquals(MathParams.get("sensor.slowAlpha"), MathFunctions.sensorStep(1), 1e-12)
        assertEquals(0.0, MathFunctions.sensorStep(-4), 0.0)
    }

    @Test fun aFasterSensorAlphaGetsCloserToOneInASecond() {
        val f = MathFunctions.byId("sensor.slow")!!
        val slow = f(1.0)
        MathParams.set("sensor.slowAlpha", 0.3)
        assertTrue(f(1.0) > slow)
    }
}
