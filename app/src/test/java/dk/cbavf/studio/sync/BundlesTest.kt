package dk.cbavf.studio.sync

import dk.cbavf.studio.draw.model.Grating
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundlesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val recipe = Recipe(canvas = CanvasDoc(100, 200, listOf(Layer(1, Grating(spacingPx = 9f)))))
    private val png = byteArrayOf(9, 8, 7, 6)

    private fun sender(): Pair<ProjectStore, dk.cbavf.studio.library.AssetRecord> {
        val store = ProjectStore(tmp.newFolder("sender"))
        val parent = store.addAsset(byteArrayOf(1), emptyList(), "draw.render", null, "maker", 1, 1, now = 1)
        val r = store.addAsset(png, listOf(parent.id), "draw.render", recipe, "maker", 100, 200, now = 2)
        store.setTags(r.id, listOf("moire"))
        return store to store.lineage()[r.id]!!
    }

    @Test
    fun anAssetArrivesWithItsIdRecipeAndTags() {
        val (from, record) = sender()
        val bundle = File(tmp.root, "b.zip")
        AssetBundle.pack(from, record, bundle)
        val to = ProjectStore(tmp.newFolder("receiver"))
        val got = AssetBundle.unpack(bundle, to)!!
        assertEquals(record.id, got.id)
        assertEquals(listOf("moire"), got.tags)
        assertEquals(record.parents, got.parents)
        assertArrayEquals(png, to.fileOf(got).readBytes())
        assertEquals(recipe, to.recipe(got))
        assertEquals(got, to.lineage()[record.id])
    }

    @Test
    fun anAssetTheLibraryHasIsNotFiledTwice() {
        val (from, record) = sender()
        val bundle = File(tmp.root, "b.zip")
        AssetBundle.pack(from, record, bundle)
        val to = ProjectStore(tmp.newFolder("receiver"))
        AssetBundle.unpack(bundle, to)
        assertNull(AssetBundle.unpack(bundle, to))
        assertEquals(1, to.lineage().assets.size)
    }

    @Test
    fun packingTheSameAssetTwiceGivesTheSameBytes() {
        val (from, record) = sender()
        val a = File(tmp.root, "a.zip").also { AssetBundle.pack(from, record, it) }
        val b = File(tmp.root, "b.zip").also { AssetBundle.pack(from, record, it) }
        assertEquals(Checksum.of(a), Checksum.of(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anAssetIdThatIsAPathIsRefused() {
        val (from, record) = sender()
        val bundle = File(tmp.root, "b.zip")
        AssetBundle.pack(from, record.copy(id = "../../x"), bundle)
        AssetBundle.unpack(bundle, ProjectStore(tmp.newFolder("receiver")))
    }

    @Test
    fun aProjectLeavesItsScratchFilesBehind() {
        val (from, _) = sender()
        File(from.tempDir, "outgoing.zip").writeBytes(png)
        File(from.dir, "canvas.json.tmp").writeBytes(png)
        File(from.dir, "patch.json.corrupt-123").writeBytes(png)
        val names = ProjectZip.files(from.dir).map { it.relativeTo(from.dir).invariantSeparatorsPath }
        assertTrue("lineage.json" in names)
        assertFalse(names.any { it.startsWith("tmp/") || it.endsWith(".tmp") || "corrupt" in it })
    }

    @Test
    fun aProjectUnpacksAsANewProjectBesideTheOthers() {
        val (from, record) = sender()
        val zip = ByteArrayOutputStream().also { ProjectZip.pack(from.dir, it) }.toByteArray()
        val root = tmp.newFolder("projects")
        File(root, "studio").mkdirs()
        val name = ProjectZip.unpack(ByteArrayInputStream(zip), root, "studio")
        assertEquals("studio-2", name)
        val copy = ProjectStore(File(root, name))
        assertEquals(2, copy.lineage().assets.size)
        assertArrayEquals(png, copy.fileOf(copy.lineage()[record.id]!!).readBytes())
    }

    @Test
    fun packingAnUnchangedProjectTwiceGivesTheSameBytes() {
        val (from, _) = sender()
        val a = ByteArrayOutputStream().also { ProjectZip.pack(from.dir, it) }.toByteArray()
        val b = ByteArrayOutputStream().also { ProjectZip.pack(from.dir, it) }.toByteArray()
        assertEquals(Checksum.of(a), Checksum.of(b))
    }

    @Test
    fun entriesOutsideTheProjectAreSkipped() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("../evil.txt")); z.write(png); z.closeEntry()
                z.putNextEntry(ZipEntry("ok.txt")); z.write(png); z.closeEntry()
            }
        }.toByteArray()
        val root = tmp.newFolder("projects")
        val name = ProjectZip.unpack(ByteArrayInputStream(zip), root, "p")
        assertFalse(File(root, "evil.txt").exists())
        assertTrue(File(root, "$name/ok.txt").exists())
    }

    private fun zipOf(vararg entries: Pair<String, Int>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, size) -> z.putNextEntry(ZipEntry(name)); z.write(ByteArray(size)); z.closeEntry() }
        }
    }.toByteArray()

    /** Unpacks [zip] under [limits] and expects it to stop, leaving no folder behind. */
    private fun assertRefused(zip: ByteArray, limits: ZipLimits) {
        val root = tmp.newFolder("projects")
        try {
            ProjectZip.unpack(ByteArrayInputStream(zip), root, "p", limits)
            org.junit.Assert.fail("unpacked past the limit")
        } catch (e: ZipLimits.TooBig) {
            assertEquals(emptyList<String>(), root.list()!!.toList())
        }
    }

    @Test
    fun tooManyEntriesStopTheUnpack() =
        assertRefused(zipOf("a" to 1, "b" to 1, "c" to 1), ZipLimits(maxEntries = 2))

    @Test
    fun tooManyBytesInAllStopTheUnpack() =
        assertRefused(zipOf("a" to 600, "b" to 600), ZipLimits(maxTotalBytes = 1000))

    @Test
    fun oneEntryTooLargeStopsTheUnpack() =
        assertRefused(zipOf("a" to 10, "big" to 5000), ZipLimits(maxEntryBytes = 4000))

    @Test
    fun aProjectWithinTheLimitsUnpacks() {
        val root = tmp.newFolder("projects")
        val name = ProjectZip.unpack(ByteArrayInputStream(zipOf("a" to 600, "b" to 600)), root, "p", ZipLimits(maxEntries = 2, maxTotalBytes = 1200, maxEntryBytes = 600))
        assertEquals(600L, File(root, "$name/b").length())
    }

    @Test
    fun anAssetTooLargeIsNotFiled() {
        val (from, record) = sender()
        val bundle = File(tmp.root, "b.zip")
        AssetBundle.pack(from, record, bundle)
        val to = ProjectStore(tmp.newFolder("receiver"))
        try {
            AssetBundle.unpack(bundle, to, ZipLimits(maxEntryBytes = 2))
            org.junit.Assert.fail("filed past the limit")
        } catch (e: ZipLimits.TooBig) {
            assertTrue(to.lineage().assets.isEmpty())
            assertEquals(emptyList<String>(), to.tempDir.list()!!.toList())
        }
    }

    @Test
    fun projectNamesAreCleaned() {
        assertEquals("my project", ProjectZip.cleanName(" my pro/ject! "))
        assertEquals("project", ProjectZip.cleanName("///"))
    }
}
