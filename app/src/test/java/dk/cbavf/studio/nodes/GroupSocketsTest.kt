package dk.cbavf.studio.nodes

import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.nodes.model.Graph
import dk.cbavf.studio.nodes.model.GraphPreset
import dk.cbavf.studio.nodes.model.GroupSocket
import dk.cbavf.studio.nodes.model.Link
import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.nodes.model.NodeGroup
import dk.cbavf.studio.nodes.model.Prop
import dk.cbavf.studio.nodes.model.SocketType
import dk.cbavf.studio.nodes.model.groupPreset
import dk.cbavf.studio.nodes.model.insertGroupPreset
import dk.cbavf.studio.nodes.model.moveGroupSocket
import dk.cbavf.studio.nodes.model.removeGroupSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSocketsTest {
    /** Group g1: inputs A, B, C into node 2; node 2 out to outputs X and Y. */
    private val body = Graph(
        nodes = listOf(Node(1, "groupin", 0f, 0f), Node(2, "blur", 200f, 0f), Node(3, "groupout", 400f, 0f)),
        links = listOf(Link(1, 0, 2, 0), Link(1, 1, 2, 1), Link(1, 2, 2, 2), Link(2, 0, 3, 0), Link(2, 0, 3, 1)),
        inputs = listOf(GroupSocket("A", SocketType.Image), GroupSocket("B", SocketType.Value), GroupSocket("C", SocketType.Value)),
        outputs = listOf(GroupSocket("X", SocketType.Image), GroupSocket("Y", SocketType.Image)),
    )

    /** Group g2 uses g1 inside, fed on g1's input B. */
    private val outer = Graph(
        nodes = listOf(Node(21, "groupin", 0f, 0f), Node(20, "group:g1", 200f, 0f)),
        links = listOf(Link(21, 0, 20, 1)),
        inputs = listOf(GroupSocket("B", SocketType.Value)),
    )

    /** The top graph: an image into g1's A and C; g1's X and Y into two viewers. */
    private val top = Graph(
        nodes = listOf(
            Node(10, "image", 0f, 0f),
            Node(11, "group:g1", 200f, 0f, mapOf("B" to Prop.Num(3f), "C" to Prop.Num(1f))),
            Node(12, "viewer", 400f, 0f),
            Node(13, "viewer", 400f, 200f),
        ),
        links = listOf(Link(10, 0, 11, 0), Link(10, 0, 11, 2), Link(11, 0, 12, 0), Link(11, 1, 13, 0)),
        groups = listOf(NodeGroup("g1", "Blur group", body), NodeGroup("g2", "Outer", outer)),
    )

    private fun Graph.group(id: String) = groups.first { it.id == id }.graph

    @Test
    fun removingAnInputDropsItsSocketAndShiftsTheRest() {
        val g = top.removeGroupSocket("g1", output = false, index = 1)
        assertEquals(listOf("A", "C"), g.group("g1").inputs.map { it.name })
        assertEquals(listOf(Link(1, 0, 2, 0), Link(1, 1, 2, 2)), g.group("g1").links.filter { it.fromNode == 1 })
    }

    @Test
    fun removingAnInputDropsItsLinksOnEveryUse() {
        val g = top.removeGroupSocket("g1", output = false, index = 1)
        // The top use: A stays, C moves down to 1.
        assertEquals(listOf(Link(10, 0, 11, 0), Link(10, 0, 11, 1)), g.links.filter { it.toNode == 11 })
        // The use inside g2 fed B, which is gone.
        assertTrue(g.group("g2").links.isEmpty())
    }

    @Test
    fun removingAnInputDropsItsFieldOnTheGroupNode() {
        val g = top.removeGroupSocket("g1", output = false, index = 1)
        assertEquals(mapOf<String, Prop>("C" to Prop.Num(1f)), g.node(11)!!.props)
    }

    @Test
    fun reorderingInputsMovesLinksWithTheirSocket() {
        val g = top.moveGroupSocket("g1", output = false, from = 2, to = 0)
        assertEquals(listOf("C", "A", "B"), g.group("g1").inputs.map { it.name })
        // Outside: A was 0, now 1; C was 2, now 0.
        assertEquals(setOf(Link(10, 0, 11, 1), Link(10, 0, 11, 0)), g.links.filter { it.toNode == 11 }.toSet())
        // Inside: each Group Input link still reaches the same inner socket.
        assertEquals(setOf(Link(1, 1, 2, 0), Link(1, 2, 2, 1), Link(1, 0, 2, 2)), g.group("g1").links.filter { it.fromNode == 1 }.toSet())
        // g2's link into B follows B to place 2.
        assertEquals(listOf(Link(21, 0, 20, 2)), g.group("g2").links)
    }

    @Test
    fun reorderingKeepsTheFieldsTyped() {
        val g = top.moveGroupSocket("g1", output = false, from = 0, to = 2)
        assertEquals(top.node(11)!!.props, g.node(11)!!.props)
    }

    @Test
    fun reorderingOutputsMovesOutgoingLinks() {
        val g = top.moveGroupSocket("g1", output = true, from = 1, to = 0)
        assertEquals(listOf("Y", "X"), g.group("g1").outputs.map { it.name })
        assertEquals(setOf(Link(11, 1, 12, 0), Link(11, 0, 13, 0)), g.links.filter { it.fromNode == 11 }.toSet())
        assertEquals(setOf(Link(2, 0, 3, 1), Link(2, 0, 3, 0)), g.group("g1").links.filter { it.toNode == 3 }.toSet())
    }

    @Test
    fun removingAnOutputDropsWhatItFed() {
        val g = top.removeGroupSocket("g1", output = true, index = 0)
        assertEquals(listOf("Y"), g.group("g1").outputs.map { it.name })
        assertEquals(listOf(Link(11, 0, 13, 0)), g.links.filter { it.fromNode == 11 })
        assertEquals(listOf(Link(2, 0, 3, 0)), g.group("g1").links.filter { it.toNode == 3 })
        // Output edits leave the fields alone.
        assertEquals(top.node(11)!!.props, g.node(11)!!.props)
    }

    @Test
    fun anInvalidSocketEditChangesNothing() {
        assertSame(top, top.removeGroupSocket("g1", output = false, index = 3))
        assertSame(top, top.moveGroupSocket("g1", output = true, from = 0, to = 2))
        assertSame(top, top.moveGroupSocket("g1", output = false, from = 1, to = 1))
        assertSame(top, top.removeGroupSocket("nope", output = false, index = 0))
    }

    @Test
    fun aGroupPresetCarriesTheGroupsItUsesInside() {
        val p = top.groupPreset("g2", "Outer blur")!!
        assertTrue(p.group)
        assertEquals("Outer blur", p.name)
        assertEquals(listOf("group:g2"), p.graph.nodes.map { it.type })
        assertEquals(setOf("g1", "g2"), p.graph.groups.map { it.id }.toSet())
    }

    @Test
    fun aPresetOfAGroupThatIsNotThereIsNull() {
        assertNull(top.groupPreset("nope", "x"))
    }

    @Test
    fun insertingAGroupPresetAddsFreshCopiesOfItsGroups() {
        val p = top.groupPreset("g2", "Outer blur")!!
        val (g, id) = top.insertGroupPreset(emptyList(), p, 50f, 60f)!!
        // g2 comes in as g3 and the g1 it uses as g4; the project's own groups stay as they were.
        assertEquals(listOf("g1", "g2", "g3", "g4"), g.groups.map { it.id })
        assertEquals(top.groups, g.groups.take(2))
        val n = g.node(id)!!
        assertEquals("group:g3", n.type)
        assertEquals(50f to 60f, n.x to n.y)
        assertEquals(listOf("groupin", "group:g4"), g.group("g3").nodes.map { it.type })
        assertEquals(body, g.group("g4"))
    }

    @Test
    fun insertingAGroupPresetInsideAGroupPutsTheNodeThere() {
        val p = top.groupPreset("g1", "Blur")!!
        val (g, id) = top.insertGroupPreset(listOf("g2"), p, 0f, 0f)!!
        assertEquals(22, id)
        assertEquals("group:g3", g.group("g2").node(id)!!.type)
        assertFalse(g.nodes.any { it.id == id && it.type == "group:g3" })
    }

    @Test
    fun aGraphPresetIsNotAGroupPreset() {
        assertNull(top.insertGroupPreset(emptyList(), GraphPreset("Plain", Graph(nodes = listOf(Node(1, "image", 0f, 0f)))), 0f, 0f))
    }

    @Test
    fun groupPresetsSurviveSavingAndOldPresetsReadAsGraphs() {
        val ser = GraphPreset.serializer()
        val p = top.groupPreset("g2", "Outer blur")!!
        assertEquals(p, ProjectStore.json.decodeFromString(ser, ProjectStore.json.encodeToString(ser, p)))
        val old = ProjectStore.json.decodeFromString(ser, """{"name":"Old","graph":{}}""")
        assertFalse(old.group)
    }
}
