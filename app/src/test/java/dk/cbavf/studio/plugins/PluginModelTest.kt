package dk.cbavf.studio.plugins

import dk.cbavf.studio.nodes.model.Node
import dk.cbavf.studio.uieditor.ControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginModelTest {
    private val sample = PluginManifest(
        id = "xy-pad", name = "XY pad", version = "1.2", kind = PluginKind.Controller,
        widgets = listOf(PluginWidget("X", ControlKind.Slider, 0f, 100f), PluginWidget("Reset", ControlKind.Button)),
    )

    @Test fun manifestRoundTrips() {
        assertEquals(sample, PluginFormat.decode(PluginFormat.encode(sample)))
    }

    @Test fun badManifestsAreRefused() {
        assertNull(PluginFormat.decode(null))
        assertNull(PluginFormat.decode("   "))
        assertNull(PluginFormat.decode("{nope"))
        assertNull(PluginFormat.decode("""{"id":"a","name":""}"""))
        assertNull(PluginFormat.decode("""{"id":"../evil","name":"Evil"}"""))
        assertNull(PluginFormat.decode("""{"id":"Has Space","name":"X"}"""))
        assertNotNull(PluginFormat.decode("""{"id":"ok-1","name":"Fine","future":true}"""))
    }

    @Test fun idsAreFileSafe() {
        assertEquals("xy-pad", PluginFormat.idFor("XY Pad!"))
        assertEquals("plugin", PluginFormat.idFor("???"))
        assertEquals(PluginFormat.idFor(PluginFormat.idFor("My  Scope")), PluginFormat.idFor("My  Scope"))
    }

    @Test fun zipEntriesCannotEscape() {
        assertEquals("manifest.json", PluginFormat.safeEntry("manifest.json"))
        assertEquals("a/b.json", PluginFormat.safeEntry("a\\b.json"))
        assertNull(PluginFormat.safeEntry("../x"))
        assertNull(PluginFormat.safeEntry("a/../../x"))
        assertNull(PluginFormat.safeEntry("/etc/passwd"))
        assertNull(PluginFormat.safeEntry("C:/x"))
        assertNull(PluginFormat.safeEntry(""))
    }

    @Test fun seedsMatchTheWidgets() {
        val s = PluginFormat.seed(sample)
        assertEquals("plugin-xy-pad", s.id)
        assertEquals(listOf("X", "Reset"), s.controls.map { it.label })
        assertEquals(ControlKind.Button, s.controls[1].kind)
    }

    @Test fun seedsAddTheBehaviourOutputs() {
        val g = PluginBehaviour.addOutput(PluginBehaviour.starter(sample.widgets), "Level")
        val s = PluginFormat.seed(sample.copy(behaviour = g))
        assertEquals(listOf("X", "Reset", "Level (out)"), s.controls.map { it.label })
    }

    @Test fun aBehaviourRoundTrips() {
        val g = PluginBehaviour.addOutput(PluginBehaviour.starter(sample.widgets), "Level")
        val m = sample.copy(behaviour = PluginBehaviour.link(g, PluginBehaviour.Source(PluginBehaviour.IN, 0, "X"), PluginBehaviour.OUT, 0))
        assertEquals(m, PluginFormat.decode(PluginFormat.encode(m)))
    }

    @Test fun decodingCleansTheBehaviour() {
        val g = PluginBehaviour.starter(sample.widgets).let { it.copy(nodes = it.nodes + Node(9, "save", 0f, 0f)) }
        val back = PluginFormat.decode(PluginFormat.encode(sample.copy(behaviour = g)))!!
        assertTrue(back.behaviour!!.nodes.none { it.type == "save" })
    }

    @Test fun oldManifestsHaveNoBehaviourOrScope() {
        val m = PluginFormat.decode("""{"id":"old","name":"Old","widgets":[{"label":"A"}]}""")!!
        assertNull(m.behaviour)
        assertNull(m.scope)
    }

    @Test fun aZipRoundTrips() {
        val bytes = ByteArrayOutputStream().also { PluginFormat.zip(sample, it) }.toByteArray()
        val files = PluginFormat.unzip(ByteArrayInputStream(bytes))
        assertEquals(setOf(PluginFormat.MANIFEST), files.keys)
        assertEquals(sample, PluginFormat.decode(files.getValue(PluginFormat.MANIFEST).toString(Charsets.UTF_8)))
    }

    @Test fun unzipSkipsEscapingAndOversizedEntries() {
        val bytes = ByteArrayOutputStream().also { raw ->
            ZipOutputStream(raw).use { z ->
                for ((name, size) in listOf("../evil.txt" to 4, "big.bin" to 5000, "ok.txt" to 4)) {
                    z.putNextEntry(ZipEntry(name)); z.write(ByteArray(size)); z.closeEntry()
                }
            }
        }.toByteArray()
        assertEquals(setOf("ok.txt"), PluginFormat.unzip(ByteArrayInputStream(bytes), maxBytes = 1000).keys)
    }

    @Test fun unzipStopsAtTheFileLimit() {
        val bytes = ByteArrayOutputStream().also { raw ->
            ZipOutputStream(raw).use { z -> repeat(5) { z.putNextEntry(ZipEntry("f$it")); z.write(1); z.closeEntry() } }
        }.toByteArray()
        assertEquals(2, PluginFormat.unzip(ByteArrayInputStream(bytes), maxFiles = 2).size)
    }

    @Test fun theBuiltInParadeIsAScope() {
        val parade = PluginStore.BUILT_IN.single { it.id == "rgb-parade" }
        assertEquals(PluginKind.Scope, parade.kind)
        assertEquals(PluginScopes.RGB_PARADE, parade.scope)
    }
}
