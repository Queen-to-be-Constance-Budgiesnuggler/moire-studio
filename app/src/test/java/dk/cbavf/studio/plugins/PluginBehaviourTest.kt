package dk.cbavf.studio.plugins

import dk.cbavf.studio.nodes.model.GROUP_IN
import dk.cbavf.studio.nodes.model.GROUP_OUT
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.plugins.PluginBehaviour.IN
import dk.cbavf.studio.plugins.PluginBehaviour.OUT
import dk.cbavf.studio.uieditor.ControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBehaviourTest {
    private val x = PluginWidget("X", ControlKind.Slider, 0f, 10f)
    private val y = PluginWidget("Y", ControlKind.Slider, 0f, 10f)
    private val on = PluginWidget("On", ControlKind.Toggle)
    private val press = PluginWidget("Press", ControlKind.Button)

    private fun ctl(g: Graph, name: String) = PluginBehaviour.Source(IN, g.inputs.indexOfFirst { it.name == name }, name)

    /** A behaviour with one output fed from [from]. */
    private fun withOutput(g: Graph, from: PluginBehaviour.Source): Graph =
        PluginBehaviour.link(PluginBehaviour.addOutput(g, "Out"), from, OUT, 0)

    @Test fun starterHasASocketForEachNumberControl() {
        val g = PluginBehaviour.starter(listOf(x, on, press, PluginWidget("Env", ControlKind.Envelope)))
        assertEquals(listOf(SocketType.Value, SocketType.Gate, SocketType.Trigger), g.inputs.map { it.type })
        assertEquals(listOf("X", "On", "Press"), g.inputs.map { it.name })
        assertEquals(GROUP_IN, g.node(IN)?.type)
        assertEquals(GROUP_OUT, g.node(OUT)?.type)
    }

    @Test fun aSliderKeepsItsRangeOnTheGroupInput() {
        val s = PluginBehaviour.starter(listOf(PluginWidget("Gain", ControlKind.Slider, 2f, 8f))).inputs.single()
        assertEquals(2f, s.min)
        assertEquals(8f, s.max)
    }

    @Test fun aControlLinkedStraightToAnOutputPassesThrough() {
        var g = PluginBehaviour.starter(listOf(x))
        g = withOutput(g, ctl(g, "X"))
        assertEquals(listOf(7f), BehaviourRun().step(g, listOf(7f), 0.0, 0f))
    }

    @Test fun anUnlinkedOutputGivesItsOwnNumber() {
        var g = PluginBehaviour.addOutput(PluginBehaviour.starter(listOf(x)), "Out")
        g = PluginBehaviour.setProp(g, OUT, "Out", Prop.Num(0.25f))
        assertEquals(listOf(0.25f), BehaviourRun().step(g, listOf(3f), 0.0, 0f))
    }

    @Test fun aMathStepWorksOnAControl() {
        var g = PluginBehaviour.starter(listOf(x))
        val (g1, id) = PluginBehaviour.addStep(g, "math")
        g = PluginBehaviour.setProp(g1, id!!, "Operation", Prop.Choice("Multiply"))
        g = PluginBehaviour.setProp(g, id, "B", Prop.Num(2f))
        g = PluginBehaviour.link(g, ctl(g, "X"), id, 0)
        g = withOutput(g, PluginBehaviour.Source(id, 0, "Math"))
        assertEquals(listOf(6f), BehaviourRun().step(g, listOf(3f), 0.0, 0f))
    }

    @Test fun onlyNumberStepsCanBeAdded() {
        val g = PluginBehaviour.starter(listOf(x))
        assertNull(PluginBehaviour.addStep(g, "blur").second)
        assertNull(PluginBehaviour.addStep(g, "sensor").second)
        assertEquals(g, PluginBehaviour.addStep(g, "image").first)
    }

    @Test fun stepsStopAtTheLimit() {
        var g = PluginBehaviour.starter(listOf(x))
        repeat(PluginBehaviour.MAX_STEPS + 3) { g = PluginBehaviour.addStep(g, "value").first }
        assertEquals(PluginBehaviour.MAX_STEPS, PluginBehaviour.steps(g).size)
    }

    @Test fun groupInputAndOutputCannotBeRemoved() {
        val g = PluginBehaviour.starter(listOf(x))
        assertEquals(g, PluginBehaviour.removeStep(g, IN))
        assertEquals(g, PluginBehaviour.removeStep(g, OUT))
    }

    @Test fun aLinkFollowsItsControlByName() {
        var g = PluginBehaviour.starter(listOf(x, y))
        g = withOutput(g, ctl(g, "Y"))
        g = PluginBehaviour.sync(listOf(y), g)
        assertEquals(listOf("Y"), g.inputs.map { it.name })
        assertEquals(Link(IN, 0, OUT, 0), g.linkInto(OUT, 0))
        assertEquals(listOf(4f), BehaviourRun().step(g, listOf(4f), 0.0, 0f))
    }

    @Test fun aRemovedControlTakesItsLinksAlong() {
        var g = PluginBehaviour.starter(listOf(x, y))
        g = withOutput(g, ctl(g, "X"))
        g = PluginBehaviour.sync(listOf(y), g)
        assertNull(g.linkInto(OUT, 0))
    }

    @Test fun syncDropsNodesThatAreNotSteps() {
        val dirty = Graph(
            nodes = listOf(Node(7, GROUP_IN, 0f, 0f), Node(8, "image", 0f, 0f), Node(9, "save", 0f, 0f), Node(10, "lfo", 0f, 0f), Node(11, GROUP_OUT, 0f, 0f)),
            links = listOf(Link(8, 0, 9, 0)),
        )
        val g = PluginBehaviour.sync(listOf(x), dirty)
        assertEquals(listOf("lfo"), PluginBehaviour.steps(g).map { it.type })
        assertEquals(setOf(GROUP_IN, GROUP_OUT, "lfo"), g.nodes.map { it.type }.toSet())
        assertTrue(g.links.isEmpty())
    }

    @Test fun syncGivesGroupInputAndOutputTheirIds() {
        val g = PluginBehaviour.sync(listOf(x), Graph(nodes = listOf(Node(1, "value", 0f, 0f), Node(5, GROUP_IN, 0f, 0f), Node(6, GROUP_OUT, 0f, 0f))))
        assertEquals(GROUP_IN, g.node(IN)?.type)
        assertEquals(GROUP_OUT, g.node(OUT)?.type)
        assertEquals(1, PluginBehaviour.steps(g).size)
    }

    @Test fun sourcesOnlyOfferWhatFits() {
        var g = PluginBehaviour.starter(listOf(x, on, press))
        val (g1, id) = PluginBehaviour.addStep(g, "gatelogic")
        g = g1
        assertEquals(listOf("On"), PluginBehaviour.sources(g, id!!, 0).map { it.label })
    }

    @Test fun sourcesNeverMakeALoop() {
        var g = PluginBehaviour.starter(listOf(x))
        val (g1, a) = PluginBehaviour.addStep(g, "math")
        val (g2, b) = PluginBehaviour.addStep(g1, "math")
        g = PluginBehaviour.link(g2, PluginBehaviour.Source(a!!, 0, ""), b!!, 0)
        val offered = PluginBehaviour.sources(g, a, 0).map { it.node }
        assertFalse(b in offered)
        assertTrue(IN in offered)
    }

    @Test fun sourceOfNamesWhatFeedsAnInput() {
        var g = PluginBehaviour.starter(listOf(x))
        g = withOutput(g, ctl(g, "X"))
        assertEquals("X", PluginBehaviour.sourceOf(g, OUT, 0)?.label)
        assertNull(PluginBehaviour.sourceOf(PluginBehaviour.unlink(g, OUT, 0), OUT, 0))
    }

    @Test fun outputNamesStayUnique() {
        val g = PluginBehaviour.addOutput(PluginBehaviour.addOutput(PluginBehaviour.starter(emptyList()), "Level"), "Level")
        assertEquals(listOf("Level", "Level+"), g.outputs.map { it.name })
    }

    @Test fun removingAnOutputKeepsTheOthersLinked() {
        var g = PluginBehaviour.starter(listOf(x, y))
        g = PluginBehaviour.addOutput(PluginBehaviour.addOutput(g, "A"), "B")
        g = PluginBehaviour.link(g, ctl(g, "X"), OUT, 0)
        g = PluginBehaviour.link(g, ctl(g, "Y"), OUT, 1)
        g = PluginBehaviour.removeOutput(g, 0)
        assertEquals(listOf("B"), g.outputs.map { it.name })
        assertEquals(listOf(9f), BehaviourRun().step(g, listOf(1f, 9f), 0.0, 0f))
    }

    @Test fun aHoldStepRemembersBetweenFrames() {
        var g = PluginBehaviour.starter(listOf(x, press))
        val (g1, id) = PluginBehaviour.addStep(g, "hold")
        g = PluginBehaviour.link(g1, ctl(g1, "X"), id!!, 0)
        g = PluginBehaviour.link(g, ctl(g, "Press"), id, 1)
        g = withOutput(g, PluginBehaviour.Source(id, 0, "Hold"))
        val run = BehaviourRun()
        assertEquals(5f, run.step(g, listOf(5f, 1f), 0.0, 0f).single())
        assertEquals(5f, run.step(g, listOf(9f, 0f), 0.1, 0.1f).single())
        assertEquals(9f, run.step(g, listOf(9f, 1f), 0.2, 0.1f).single())
    }

    @Test fun anLfoStepMovesWithTime() {
        var g = PluginBehaviour.starter(emptyList())
        val (g1, id) = PluginBehaviour.addStep(g, "lfo")
        g = PluginBehaviour.setProp(g1, id!!, "Shape", Prop.Choice("Sine"))
        g = withOutput(g, PluginBehaviour.Source(id, 0, "LFO"))
        val run = BehaviourRun()
        assertNotEquals(run.step(g, emptyList(), 0.0, 0f).single(), run.step(g, emptyList(), 1.25, 1.25f).single(), 1e-3f)
    }

    @Test fun outputsAreListedAndLabelled() {
        val g = PluginBehaviour.addOutput(PluginBehaviour.starter(emptyList()), "Level")
        val m = PluginManifest("p", "P", behaviour = g)
        assertEquals(listOf("Level"), PluginBehaviour.outputs(m).map { it.name })
        assertEquals("Level (out)", PluginBehaviour.outputLabel("Level"))
        assertTrue(PluginBehaviour.outputs(PluginManifest("q", "Q")).isEmpty())
    }
}
