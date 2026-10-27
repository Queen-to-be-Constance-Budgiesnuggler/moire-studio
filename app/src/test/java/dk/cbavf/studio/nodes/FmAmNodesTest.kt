package dk.cbavf.studio.nodes

import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.math.FmAm
import dk.cbavf.studio.math.FmAmMode
import dk.cbavf.studio.math.FmAmSetup
import dk.cbavf.studio.nodes.model.Catalog
import dk.cbavf.studio.nodes.model.Evaluator
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.ImageBackend
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeValue
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.TimeEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The FM/AM and FM/AM Image nodes, evaluated as Live does. */
class FmAmNodesTest {
    /** Records what the image node hands to the backend. */
    private class Recorder : ImageBackend {
        var type: String? = null
        var nums: List<Float> = emptyList()
        var props: Map<String, String> = emptyMap()
        override fun libraryImage(assetId: String, maxPx: Int) = NodeValue.Img("pic", 100, 50)
        override fun drawCanvas(): CanvasDoc? = null
        override fun render(doc: CanvasDoc, width: Int, height: Int, paper: Boolean, maxPx: Int) = NodeValue.Img("", width, height)
        override fun process(type: String, inputs: List<NodeValue?>, nums: List<Float>, props: Map<String, String>, maxPx: Int): NodeValue.Img {
            this.type = type
            this.nums = nums
            this.props = props
            return NodeValue.Img("out", 100, 50)
        }
    }

    private fun value(g: Graph, id: Int, t: Double, socket: Int = 0): Float =
        (Evaluator(g, Recorder(), 1024, env = TimeEnv(t, 0.016f)).value(id, socket) as NodeValue.Num).v

    private fun fmam(mode: String, vararg nums: Pair<String, Float>) =
        Node(1, "fmam", 0f, 0f, mapOf<String, Prop>("Mode" to Prop.Choice(mode)) + nums.map { it.first to Prop.Num(it.second) })

    @Test fun bothNodesAreInTheCatalog() {
        assertNotNull(Catalog["fmam"])
        assertNotNull(Catalog["fmamimage"])
    }

    @Test fun theValueFollowsTheChosenModeBetweenMinAndMax() {
        val g = Graph(listOf(fmam("Ring", "Carrier Hz" to 3f, "Modulator Hz" to 0.5f, "Min" to 10f, "Max" to 20f)))
        val s = FmAmSetup(FmAmMode.Ring, 3.0, 0.5, 0.5, 2.0)
        val t = 0.7
        assertEquals(FmAm.scaled(FmAm.sample(t, s), 10.0, 20.0).toFloat(), value(g, 1, t), 1e-4f)
    }

    @Test fun carrierAndModulatorComeOutOnTheirOwn() {
        val g = Graph(listOf(fmam("FM", "Carrier Hz" to 2f, "Modulator Hz" to 0.25f)))
        val s = FmAmSetup(FmAmMode.FM, 2.0, 0.25)
        assertEquals(FmAm.scaled(FmAm.carrier(0.4, s), 0.0, 1.0).toFloat(), value(g, 1, 0.4, socket = 1), 1e-5f)
        assertEquals(FmAm.scaled(FmAm.modulator(0.4, s), 0.0, 1.0).toFloat(), value(g, 1, 0.4, socket = 2), 1e-5f)
    }

    @Test fun withoutLiveTheValueSitsInTheMiddle() {
        // At time 0 every sine is 0, the middle of Min..Max.
        val g = Graph(listOf(fmam("AM", "Min" to 0f, "Max" to 8f)))
        assertEquals(4f, value(g, 1, 0.0), 1e-5f)
    }

    @Test fun anUnknownModeIsAM() {
        assertEquals(FmAmMode.AM, Evaluator.fmAmMode("Banana"))
        assertEquals(FmAmMode.Ring, Evaluator.fmAmMode("Ring"))
    }

    @Test fun theImageNodeHandsItsSettingsToTheBackend() {
        val rec = Recorder()
        val g = Graph(
            listOf(
                Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))),
                Node(2, "fmamimage", 0f, 0f, mapOf("Carrier" to Prop.Num(12f), "Effect" to Prop.Choice("Shift"))),
            ),
            listOf(Link(1, 0, 2, 0)),
        )
        val out = Evaluator(g, rec, 1024).value(2) as NodeValue.Img
        assertEquals("out", out.handle)
        assertEquals("fmamimage", rec.type)
        assertEquals(12f, rec.nums[1], 0f)
        assertEquals("Shift", rec.props["Effect"])
        assertEquals("Down", rec.props["Direction"])
    }

    @Test fun theImageNodeWithoutAPictureGivesNothing() {
        val g = Graph(listOf(Node(1, "fmamimage", 0f, 0f)))
        assertNull(Evaluator(g, Recorder(), 1024).value(1))
    }

    @Test fun aMutedImageNodePassesThePictureThrough() {
        val g = Graph(
            listOf(Node(1, "image", 0f, 0f, mapOf("asset" to Prop.Text("a"))), Node(2, "fmamimage", 0f, 0f, muted = true)),
            listOf(Link(1, 0, 2, 0)),
        )
        assertEquals("pic", (Evaluator(g, Recorder(), 1024).value(2) as NodeValue.Img).handle)
    }
}
