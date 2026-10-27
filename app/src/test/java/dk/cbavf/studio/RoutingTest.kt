package dk.cbavf.studio

import dk.cbavf.studio.routing.model.Cable
import dk.cbavf.studio.routing.model.JackType
import dk.cbavf.studio.routing.model.RoutingDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    @Test
    fun cablesCheckJacksTypesAndReplace() {
        var d = RoutingDoc()
        d = d.connect("draw.canvas", "compose.in1")
        assertEquals(listOf(Cable("draw.canvas", "compose.in1")), d.cables)
        // An output feeds any number of inputs.
        d = d.connect("draw.canvas", "develop.in")
        assertEquals(2, d.cables.size)
        // A second cable into the same input replaces the first.
        d = d.connect("animate.frame", "compose.in1")
        assertEquals("animate.frame", d.sourceOf("compose.in1"))
        assertEquals(2, d.cables.size)
        // Unknown jacks, input→output and output→output change nothing.
        assertEquals(d, d.connect("draw.nope", "compose.in2"))
        assertEquals(d, d.connect("compose.in2", "draw.canvas"))
        assertEquals(d, d.connect("draw.canvas", "compose.image"))
        assertTrue(JackType.Image.accepts(JackType.Canvas))
        assertTrue(!JackType.Canvas.accepts(JackType.Image))
    }

    @Test
    fun loopsBetweenModulesAreRefused() {
        var d = RoutingDoc().connect("compose.image", "develop.in")
        // Develop feeding Compose would close Compose → Develop → Compose.
        assertEquals(d, d.connect("develop.image", "compose.in1"))
        // Longer loops too: Develop → Glitch, then Glitch → Compose closes the loop.
        d = d.connect("develop.image", "glitch.in")
        assertEquals(d, d.connect("glitch.image", "compose.in2"))
        // A module never feeds itself.
        assertEquals(d, d.connect("nodes.out", "nodes.in1"))
        // Replacing the cable that made the loop makes room for it.
        d = d.connect("draw.canvas", "develop.in")
        assertEquals("glitch.image", d.connect("glitch.image", "compose.in2").sourceOf("compose.in2"))
    }

    @Test
    fun unplugging() {
        val d = RoutingDoc().connect("draw.canvas", "compose.in1").connect("draw.canvas", "nodes.in1")
        assertEquals(setOf("draw.canvas"), d.patchedOutputs)
        assertEquals(listOf("compose.in1", "nodes.in1"), d.targetsOf("draw.canvas"))
        assertEquals(listOf(Cable("draw.canvas", "nodes.in1")), d.disconnect("compose.in1").cables)
    }

    @Test
    fun numbersTravelOnCablesByTheirJackTypes() {
        // a gate fits a gate input and a value input; a value fits only a value; a trigger and a gate do not mix
        assertTrue(JackType.Gate.accepts(JackType.Gate))
        assertTrue(JackType.Value.accepts(JackType.Gate))
        assertTrue(JackType.Value.accepts(JackType.Trigger))
        assertTrue(JackType.Value.accepts(JackType.Value))
        assertTrue(!JackType.Gate.accepts(JackType.Value))
        assertTrue(!JackType.Gate.accepts(JackType.Trigger))
        assertTrue(!JackType.Trigger.accepts(JackType.Gate))
        assertTrue(!JackType.Image.accepts(JackType.Gate))
        assertTrue(!JackType.Gate.accepts(JackType.Image))
    }

    @Test
    fun theStudioGatesPatchIntoNodeLabSignals() {
        val d = RoutingDoc().connect("gates.a", "nodes.sig1").connect("gates.clock", "nodes.sig2")
        assertEquals(listOf(Cable("gates.a", "nodes.sig1"), Cable("gates.clock", "nodes.sig2")), d.cables)
        // a gate does not go into an image input
        assertEquals(d, d.connect("gates.b", "develop.in"))
    }

    @Test
    fun sensorsPatchIntoGlitchSeed() {
        val d = RoutingDoc().connect("sensors.seed", "glitch.seed").connect("sensors.roll", "nodes.sig1")
        assertEquals(listOf(Cable("sensors.seed", "glitch.seed"), Cable("sensors.roll", "nodes.sig1")), d.cables)
        // a number does not go into the image input of the same unit
        assertEquals(d, d.connect("sensors.light", "glitch.in"))
    }
}

