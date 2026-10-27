package dk.cbavf.studio.library

import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.bridge.model.BridgeDoc
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.develop.model.Preset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import dk.cbavf.studio.patch.model.PatchDoc
import dk.cbavf.studio.sequence.model.SequenceDoc
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * A project on disk:
 * ```
 * <dir>/lineage.json   generation tree: one small record per asset
 * <dir>/canvas.json    autosaved Draw canvas
 * <dir>/animation.json autosaved Animate frames
 * <dir>/sequence.json  autosaved Sequence timeline
 * <dir>/bridge.json    pending sends and watch-folder state
 * <dir>/patch.json     patch bay routes and LFOs
 * <dir>/develop.json   the Develop edit in progress
 * <dir>/compose.json   the Compose document in progress
 * <dir>/nodes.json     the Node Lab graph in progress
 * <dir>/node-presets.json saved graphs
 * <dir>/presets.json   saved Develop looks
 * <dir>/luts/<id>.cube imported colour lookup tables
 * <dir>/assets/<id>.<png|gif|mp4>   rendered images and exports, never modified after writing
 * <dir>/assets/<id>.recipe.json     how the asset was made (can be large)
 * ```
 * JSON is streamed to and from disk, never held as one big string. Writes go through a temp
 * file and rename, so a crash never leaves half a file.
 */
@OptIn(ExperimentalSerializationApi::class)
class ProjectStore(val dir: File) {
    private val lineageFile = File(dir, "lineage.json")
    private val canvasFile = File(dir, "canvas.json")
    private val animationFile = File(dir, "animation.json")
    private val sequenceFile = File(dir, "sequence.json")
    private val bridgeFile = File(dir, "bridge.json")
    private val patchFile = File(dir, "patch.json")
    private val developFile = File(dir, "develop.json")
    private val composeFile = File(dir, "compose.json")
    private val routingFile = File(dir, "routing.json")
    private val uiLayoutFile = File(dir, "ui-layout.json")
    private val sensorTakeFile = File(dir, "sensor-take.json")
    private val modulePresetsFile = File(dir, "module-presets.json")
    private val rackPresetsFile = File(dir, "rack-presets.json")
    private val nodesFile = File(dir, "nodes.json")
    private val nodePresetsFile = File(dir, "node-presets.json")
    private val presetsFile = File(dir, "presets.json")
    private val assetsDir = File(dir, "assets")

    /** Scratch space for exports in progress, on the same volume as the assets so the final move is a rename. */
    val tempDir: File get() = File(dir, "tmp").apply { mkdirs() }

    @Synchronized
    fun lineage(): Lineage = Lineage(readLineage().assets)

    /** Writes the image and appends its record; returns the stored record. */
    @Synchronized
    fun addAsset(
        png: ByteArray,
        parents: List<String>,
        operation: String,
        recipe: Recipe?,
        device: String,
        widthPx: Int,
        heightPx: Int,
        now: Long = System.currentTimeMillis(),
        /** Share another asset's recipe file instead of writing a copy (a barrier and its image). */
        recipeOf: AssetRecord? = null,
    ): AssetRecord = add("png", parents, operation, device, widthPx, heightPx, recipe, recipeOf, now) { file ->
        writeAtomic(file) { it.write(png) }
    }

    /** Moves an already written file (an encoded GIF or MP4) into the project as a new asset. */
    @Synchronized
    fun addAssetFile(
        source: File,
        extension: String,
        parents: List<String>,
        operation: String,
        device: String,
        widthPx: Int,
        heightPx: Int,
        recipe: Recipe?,
        now: Long = System.currentTimeMillis(),
    ): AssetRecord = add(extension, parents, operation, device, widthPx, heightPx, recipe, null, now) { file ->
        if (!source.renameTo(file)) {
            source.copyTo(file, overwrite = true)
            source.delete()
        }
    }

    /**
     * Files an asset sent from the other phone under its own id, so its parents and children there
     * still link up here when they come too. Its file is moved in from [source]. Returns null,
     * changing nothing, when this Library already has that id.
     */
    @Synchronized
    fun addReceived(record: AssetRecord, source: File, recipe: Recipe?): AssetRecord? {
        require(SAFE_ID.matches(record.id)) { "bad asset id" }
        val extension = record.file.substringAfterLast('.').lowercase()
        require(SAFE_EXTENSION.matches(extension)) { "bad file type" }
        val all = readLineage().assets
        if (all.any { it.id == record.id }) return null
        val relative = "assets/${record.id}.$extension"
        assetsDir.mkdirs()
        val file = File(dir, relative)
        if (!source.renameTo(file)) {
            source.copyTo(file, overwrite = true)
            source.delete()
        }
        // The other phone's export uri and recipe path mean nothing here.
        val bare = record.copy(file = relative, exportedUri = null, recipeFile = null, recipe = null, animation = null, sequence = null)
        val stored = if (recipe != null && !recipe.isEmpty) withRecipeFile(bare, recipe) else bare
        writeLineage(LineageFile(all + stored))
        return stored
    }

    private fun add(
        extension: String,
        parents: List<String>,
        operation: String,
        device: String,
        widthPx: Int,
        heightPx: Int,
        recipe: Recipe?,
        recipeOf: AssetRecord?,
        now: Long,
        write: (File) -> Unit,
    ): AssetRecord {
        val id = "a" + now.toString(36) + "-" + UUID.randomUUID().toString().take(6)
        val relative = "assets/$id.$extension"
        assetsDir.mkdirs()
        write(File(dir, relative))
        val bare = AssetRecord(id, relative, now, parents, operation, null, device, widthPx, heightPx)
        val record = when {
            recipeOf != null -> bare.copy(recipeFile = recipeOf.recipeFile, summary = recipeOf.summary)
            recipe != null && !recipe.isEmpty -> withRecipeFile(bare, recipe)
            else -> bare
        }
        writeLineage(LineageFile(readLineage().assets + record))
        return record
    }

    /** The asset's recipe. Can be large: call it off the main thread. */
    fun recipe(record: AssetRecord): Recipe? {
        if (record.hasInlineRecipe) return Recipe(record.recipe, record.animation, record.sequence)
        val f = File(dir, record.recipeFile ?: return null)
        return readOrNull(f, Recipe.serializer())
    }

    @Synchronized
    fun update(record: AssetRecord) {
        writeLineage(LineageFile(readLineage().assets.map { if (it.id == record.id) record else it }))
    }

    fun fileOf(record: AssetRecord): File = File(dir, record.file)

    /**
     * Removes an asset that nothing descends from: its record, its file and its recipe file (unless
     * another asset shares it). Returns false, changing nothing, if it has children: deleting it
     * would break the generation tree.
     */
    @Synchronized
    fun delete(record: AssetRecord): Boolean {
        val all = readLineage().assets
        if (all.any { record.id in it.parents }) return false
        val rest = all.filterNot { it.id == record.id }
        writeLineage(LineageFile(rest))
        File(dir, record.file).delete()
        record.recipeFile?.takeIf { f -> rest.none { it.recipeFile == f } }?.let { File(dir, it).delete() }
        return true
    }

    /** Deletes [plan]'s assets in its order (children first); returns how many were removed. */
    fun deleteAll(plan: DeletePlan): Int = plan.order.count { delete(it) }

    /** Changes several records at once (light table marks). */
    @Synchronized
    fun updateMany(ids: Set<String>, f: (AssetRecord) -> AssetRecord) {
        writeLineage(LineageFile(readLineage().assets.map { if (it.id in ids) f(it) else it }))
    }

    /** The lab notebook's note on an entry; null or blank removes it. */
    @Synchronized
    fun setNote(id: String, note: String?) {
        writeLineage(LineageFile(readLineage().assets.map { if (it.id == id) it.copy(note = note?.takeIf { n -> n.isNotBlank() }) else it }))
    }

    /** Marks [id] as a rerun of the experiment first run as [origin]. */
    @Synchronized
    fun setRerunOf(id: String, origin: String) {
        writeLineage(LineageFile(readLineage().assets.map { if (it.id == id) it.copy(rerunOf = origin) else it }))
    }

    @Synchronized
    fun setTags(id: String, tags: List<String>) {
        writeLineage(LineageFile(readLineage().assets.map { if (it.id == id) it.copy(tags = tags.distinct().sorted()) else it }))
    }

    @Synchronized
    fun saveCanvas(autosave: Autosave) = writeJson(canvasFile, Autosave.serializer(), autosave)

    @Synchronized
    fun loadCanvas(): Autosave? = readOrNull(canvasFile, Autosave.serializer())

    @Synchronized
    fun saveSequence(sequence: SequenceDoc) = writeJson(sequenceFile, SequenceDoc.serializer(), sequence)

    @Synchronized
    fun loadSequence(): SequenceDoc? = readOrNull(sequenceFile, SequenceDoc.serializer())

    @Synchronized
    fun saveNodes(g: dk.cbavf.studio.nodes.model.Graph) = writeJson(nodesFile, dk.cbavf.studio.nodes.model.Graph.serializer(), g)

    @Synchronized
    fun loadNodes(): dk.cbavf.studio.nodes.model.Graph? = readOrNull(nodesFile, dk.cbavf.studio.nodes.model.Graph.serializer())

    @Synchronized
    fun saveNodePresets(p: List<dk.cbavf.studio.nodes.model.GraphPreset>) =
        writeJson(nodePresetsFile, ListSerializer(dk.cbavf.studio.nodes.model.GraphPreset.serializer()), p)

    @Synchronized
    fun loadNodePresets(): List<dk.cbavf.studio.nodes.model.GraphPreset> =
        readOrNull(nodePresetsFile, ListSerializer(dk.cbavf.studio.nodes.model.GraphPreset.serializer())).orEmpty()

    @Synchronized
    fun saveModulePresets(p: Map<String, List<dk.cbavf.studio.routing.ModulePreset>>) =
        writeJson(modulePresetsFile, kotlinx.serialization.builtins.MapSerializer(String.serializer(), ListSerializer(dk.cbavf.studio.routing.ModulePreset.serializer())), p)

    @Synchronized
    fun loadModulePresets(): Map<String, List<dk.cbavf.studio.routing.ModulePreset>> =
        readOrNull(modulePresetsFile, kotlinx.serialization.builtins.MapSerializer(String.serializer(), ListSerializer(dk.cbavf.studio.routing.ModulePreset.serializer()))).orEmpty()

    /** The rack presets saved in this project (the built-in ones live in the app). */
    @Synchronized
    fun saveRackPresets(p: List<dk.cbavf.studio.routing.model.RackPreset>) =
        writeJson(rackPresetsFile, ListSerializer(dk.cbavf.studio.routing.model.RackPreset.serializer()), p)

    @Synchronized
    fun loadRackPresets(): List<dk.cbavf.studio.routing.model.RackPreset> =
        readOrNull(rackPresetsFile, ListSerializer(dk.cbavf.studio.routing.model.RackPreset.serializer())).orEmpty()

    @Synchronized
    fun saveRouting(doc: dk.cbavf.studio.routing.model.RoutingDoc) =
        writeJson(routingFile, dk.cbavf.studio.routing.model.RoutingDoc.serializer(), doc)

    @Synchronized
    fun loadRouting(): dk.cbavf.studio.routing.model.RoutingDoc? =
        readOrNull(routingFile, dk.cbavf.studio.routing.model.RoutingDoc.serializer())

    /** The UI Editor's canvas: where every control sits, and the cables patched on the back. */
    /** The recorded sensor stream of this project (one take: the last one recorded). */
    @Synchronized
    fun saveSensorTake(take: dk.cbavf.studio.sensors.SensorTake) =
        writeJson(sensorTakeFile, dk.cbavf.studio.sensors.SensorTake.serializer(), take)

    @Synchronized
    fun loadSensorTake(): dk.cbavf.studio.sensors.SensorTake? =
        readOrNull(sensorTakeFile, dk.cbavf.studio.sensors.SensorTake.serializer())

    @Synchronized
    fun saveUiLayout(doc: dk.cbavf.studio.uieditor.UiLayout) =
        writeJson(uiLayoutFile, dk.cbavf.studio.uieditor.UiLayout.serializer(), doc)

    @Synchronized
    fun loadUiLayout(): dk.cbavf.studio.uieditor.UiLayout? =
        readOrNull(uiLayoutFile, dk.cbavf.studio.uieditor.UiLayout.serializer())

    @Synchronized
    fun saveCompose(doc: dk.cbavf.studio.compose.model.ComposeDoc) =
        writeJson(composeFile, dk.cbavf.studio.compose.model.ComposeDoc.serializer(), doc)

    @Synchronized
    fun loadCompose(): dk.cbavf.studio.compose.model.ComposeDoc? =
        readOrNull(composeFile, dk.cbavf.studio.compose.model.ComposeDoc.serializer())

    @Synchronized
    fun saveDevelop(doc: DevelopDoc) = writeJson(developFile, DevelopDoc.serializer(), doc)

    @Synchronized
    fun loadDevelop(): DevelopDoc? = readOrNull(developFile, DevelopDoc.serializer())

    @Synchronized
    fun savePresets(presets: List<Preset>) = writeJson(presetsFile, ListSerializer(Preset.serializer()), presets)

    @Synchronized
    fun loadPresets(): List<Preset> = readOrNull(presetsFile, ListSerializer(Preset.serializer())).orEmpty()

    /** Copies an imported .cube into the project; returns its project-relative path. */
    fun addLut(name: String, text: String): String {
        val safe = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val relative = "luts/${System.currentTimeMillis().toString(36)}-$safe.cube"
        writeAtomic(File(dir, relative)) { it.write(text.toByteArray()) }
        return relative
    }

    fun file(relative: String): File = File(dir, relative)

    @Synchronized
    fun savePatch(patch: PatchDoc) = writeJson(patchFile, PatchDoc.serializer(), patch)

    @Synchronized
    fun loadPatch(): PatchDoc? = readOrNull(patchFile, PatchDoc.serializer())

    @Synchronized
    fun saveBridge(bridge: BridgeDoc) = writeJson(bridgeFile, BridgeDoc.serializer(), bridge)

    @Synchronized
    fun loadBridge(): BridgeDoc? = readOrNull(bridgeFile, BridgeDoc.serializer())

    @Synchronized
    fun saveAnimation(animation: AnimationDoc) = writeJson(animationFile, AnimationDoc.serializer(), animation)

    @Synchronized
    fun loadAnimation(): AnimationDoc? = readOrNull(animationFile, AnimationDoc.serializer())

    /** Reads the index, first moving any inline recipes (the older format) out to recipe files. */
    private fun readLineage(): LineageFile {
        val l = readOrNull(lineageFile, LineageFile.serializer()) ?: return LineageFile()
        if (l.assets.none { it.hasInlineRecipe }) return l
        val migrated = LineageFile(
            l.assets.map { r ->
                if (!r.hasInlineRecipe) r
                else withRecipeFile(r.copy(recipe = null, animation = null, sequence = null), Recipe(r.recipe, r.animation, r.sequence))
            }
        )
        writeLineage(migrated)
        return migrated
    }

    private fun withRecipeFile(record: AssetRecord, recipe: Recipe): AssetRecord {
        val relative = "assets/${record.id}.recipe.json"
        writeJson(File(dir, relative), Recipe.serializer(), recipe)
        return record.copy(recipeFile = relative, summary = recipe.summary)
    }

    private fun writeLineage(l: LineageFile) = writeJson(lineageFile, LineageFile.serializer(), l)

    private fun <T> writeJson(f: File, serializer: KSerializer<T>, value: T) =
        writeAtomic(f) { json.encodeToStream(serializer, value, it) }

    /**
     * Null when the file is missing. A file that is not valid JSON for [serializer] is kept aside
     * for inspection instead of being overwritten. Anything else (out of memory, I/O) is thrown:
     * a file that could not be read is not a corrupt file, and setting it aside would lose it.
     */
    private fun <T> readOrNull(f: File, serializer: KSerializer<T>): T? {
        if (!f.exists()) return null
        return try {
            f.inputStream().buffered().use { json.decodeFromStream(serializer, it) }
        } catch (e: SerializationException) {
            setAside(f)
        } catch (e: IllegalArgumentException) {
            setAside(f)
        }
    }

    private fun setAside(f: File): Nothing? {
        f.renameTo(File(f.parentFile, f.name + ".corrupt-" + System.currentTimeMillis()))
        return null
    }

    private fun writeAtomic(target: File, write: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().buffered().use(write)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write $target" }
        }
    }

    companion object {
        private val open = HashMap<String, ProjectStore>()

        /** One store per directory per process, so the app and its services never write the index at once. */
        @Synchronized
        fun open(dir: File): ProjectStore = open.getOrPut(dir.absolutePath) { ProjectStore(dir) }

        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
