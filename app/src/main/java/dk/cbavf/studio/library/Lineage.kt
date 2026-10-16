package dk.cbavf.studio.library

import dk.cbavf.studio.animate.model.AnimationDoc
import dk.cbavf.studio.camera.model.CaptureInfo
import dk.cbavf.studio.develop.model.DevelopDoc
import dk.cbavf.studio.fft.model.FftDoc
import dk.cbavf.studio.glitch.model.GlitchDoc
import dk.cbavf.studio.draw.model.Layer
import dk.cbavf.studio.sequence.model.SequenceDoc
import kotlinx.serialization.Serializable

/** Everything needed to re-render a Draw canvas exactly: the recipe for a "draw.render" asset. */
@Serializable
data class CanvasDoc(
    val widthPx: Int,
    val heightPx: Int,
    val layers: List<Layer>,
)

/**
 * How an asset was made, kept in its own file next to the asset: a recipe can hold whole
 * animations of hand-drawn strokes, far too much to keep in the lineage index.
 */
@Serializable
data class Recipe(
    val canvas: CanvasDoc? = null,
    val animation: AnimationDoc? = null,
    val sequence: SequenceDoc? = null,
    val capture: CaptureInfo? = null,
    val develop: DevelopDoc? = null,
    val compose: dk.cbavf.studio.compose.model.ComposeDoc? = null,
    /** A routing-nodes graph that rendered this image. */
    val graph: dk.cbavf.studio.nodes.model.Graph? = null,
    val fft: FftDoc? = null,
    val glitch: GlitchDoc? = null,
) {
    /** One line for the Library, so it never has to load the recipe itself. */
    val summary: String
        get() = listOfNotNull(
            canvas?.let { "${it.layers.size} layers" },
            animation?.let { "${it.frames.size} frames, strip ${it.stripPx} px" },
            sequence?.let { "${it.items.size} items @ ${it.rateHz} Hz" },
            capture?.summary,
            develop?.summary,
            compose?.summary,
            graph?.let { "${it.nodes.size} nodes" },
            fft?.summary,
            glitch?.summary,
        ).joinToString(" · ")

    val isEmpty: Boolean get() = canvas == null && animation == null && sequence == null && capture == null && develop == null && compose == null && graph == null && fft == null && glitch == null
}

/**
 * One node in the generation tree. Originals are never modified: every operation writes a new
 * asset that points at its parents.
 */
@Serializable
data class AssetRecord(
    val id: String,
    /** Image file, relative to the project directory. */
    val file: String,
    val createdAt: Long,
    val parents: List<String>,
    /** What made it, e.g. "draw.render", "animate.interleave", "sequence.gif". External steps will read "external:<app>". */
    val operation: String,
    /** Legacy inline recipe from before recipe files; moved out on load. */
    val recipe: CanvasDoc? = null,
    val device: String,
    val widthPx: Int,
    val heightPx: Int,
    val tags: List<String> = emptyList(),
    /** Shared copy in Pictures/Studio or Movies/Studio (content:// uri), if exported. */
    val exportedUri: String? = null,
    /** Legacy inline recipe (Animate); moved out on load. */
    val animation: AnimationDoc? = null,
    /** Legacy inline recipe (Sequence); moved out on load. */
    val sequence: SequenceDoc? = null,
    /** Recipe file, relative to the project directory. */
    val recipeFile: String? = null,
    val summary: String? = null,
) {
    val isVideo: Boolean get() = file.endsWith(".mp4")

    /** Still images that other rooms can use as frames. */
    val isStill: Boolean get() = file.substringAfterLast('.').lowercase() in STILL_EXTENSIONS

    val hasInlineRecipe: Boolean get() = recipe != null || animation != null || sequence != null

    val hasRecipe: Boolean get() = recipeFile != null || hasInlineRecipe

    /** Width / height for layout; 1 when the size is unknown (aspectRatio rejects 0). */
    val aspect: Float get() = if (widthPx > 0 && heightPx > 0) widthPx.toFloat() / heightPx else 1f

    /** The room that can reopen this asset's recipe ("draw", "animate", "sequence", …). */
    val room: String
        get() = when (val kind = operation.substringBefore('.')) {
            // A Loop capture's recipe is its camera settings: it reopens in Camera.
            "loop" -> "camera"
            else -> kind
        }

    companion object {
        val STILL_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
    }
}

/** Library images placed on this canvas (image layers): parents of anything made from it. */
fun CanvasDoc.imageAssetIds(): List<String> = layers.mapNotNull { (it.content as? dk.cbavf.studio.draw.model.ImageLayer)?.assetId }.distinct()

/** The working Draw canvas, kept between sessions. */
@Serializable
data class Autosave(val doc: CanvasDoc, val sourceAssetId: String? = null)

@Serializable
data class LineageFile(val assets: List<AssetRecord> = emptyList())

/** Read-only queries over the generation tree. */
class Lineage(val assets: List<AssetRecord>) {
    private val byId = assets.associateBy { it.id }

    operator fun get(id: String): AssetRecord? = byId[id]

    fun children(id: String): List<AssetRecord> = assets.filter { id in it.parents }

    /** Direct parents (an FFT phase swap or a style mix has two). */
    fun parents(id: String): List<AssetRecord> = byId[id]?.parents.orEmpty().mapNotNull { byId[it] }

    /** Every tag in use, for the filter and for quick tagging. */
    fun allTags(): List<String> = assets.flatMap { it.tags }.distinct().sorted()

    /** First parent chain up to the root, nearest first. Stops on missing parents and cycles. */
    fun ancestors(id: String): List<AssetRecord> {
        val out = mutableListOf<AssetRecord>()
        val seen = mutableSetOf(id)
        var current = byId[id]
        while (current != null) {
            val parentId = current.parents.firstOrNull() ?: break
            if (!seen.add(parentId)) break
            current = byId[parentId] ?: break
            out += current
        }
        return out
    }

    /** Number of steps from the root (a root is generation 0). */
    fun generation(id: String): Int = ancestors(id).size

    fun newestFirst(): List<AssetRecord> = assets.sortedByDescending { it.createdAt }
}
