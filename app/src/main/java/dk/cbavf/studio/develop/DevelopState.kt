package dk.cbavf.studio.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.develop.model.DevelopDoc
import androidx.compose.runtime.mutableIntStateOf
import dk.cbavf.studio.develop.model.MaskLayer
import dk.cbavf.studio.develop.model.MaskKind
import dk.cbavf.studio.develop.model.Mask
import dk.cbavf.studio.develop.model.Lut
import dk.cbavf.studio.develop.model.Preset
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.Recipe
import dk.cbavf.studio.library.exportToMedia
import java.io.File

enum class DevelopTab { Light, Color, Mixer, Curve, Lut, Mask, Style, Presets }

/** The Develop edit in progress. Edits replace [doc]; undo keeps snapshots like the other rooms. */
class DevelopState : dk.cbavf.studio.ui.HistoryView {
    var doc by mutableStateOf(DevelopDoc())
        private set
    var tab by mutableStateOf(DevelopTab.Light)
    var presets by mutableStateOf<List<Preset>>(emptyList())
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<DevelopDoc>()
    private val future = ArrayDeque<DevelopDoc>()

    // --- The History panel (a list of the snapshots this room keeps; labels are generic) -----------
    private var rev by androidx.compose.runtime.mutableIntStateOf(0)

    override val timeline: List<String>
        get() { rev; return List(history.size + 1 + future.size) { if (it == 0) "Start" else "Edit $it" } }

    override val position: Int get() { rev; return history.size }

    override fun jumpTo(i: Int) {
        val n = i - history.size
        if (n < 0) repeat(-n) { undo() } else repeat(n) { redo() }
    }
    private var lastKey: Any? = null

    /** Consecutive edits with the same non-null [key] (one slider drag) are one undo step. */
    fun edit(key: Any? = null, f: (DevelopDoc) -> DevelopDoc) {
        val next = f(doc)
        if (next == doc) return
        if (key == null || key != lastKey) {
            history.addLast(doc)
            if (history.size > MAX_UNDO) history.removeFirst()
            canUndo = true
        }
        lastKey = key
        doc = next
        future.clear()
        canRedo = false
        rev++
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        future.addLast(doc)
        doc = previous
        lastKey = null
        canUndo = history.isNotEmpty()
        canRedo = true
        rev++
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        history.addLast(doc)
        doc = next
        lastKey = null
        canUndo = true
        canRedo = future.isNotEmpty()
        rev++
    }

    /** Starts editing [asset] with a clean slate (or with [recipe], to reopen a saved edit). */
    fun open(asset: AssetRecord, recipe: DevelopDoc? = null) {
        val next = recipe?.copy(sourceAssetId = recipe.sourceAssetId ?: asset.id) ?: DevelopDoc(sourceAssetId = asset.id)
        edit { next }
    }

    /** Reopens a saved edit; undoable like any other edit. */
    fun open(d: DevelopDoc) = edit { d }

    fun load(d: DevelopDoc) {
        doc = d
        history.clear()
        future.clear()
        canUndo = false
        canRedo = false
        rev++
        lastKey = null
    }

    fun reset() = edit { DevelopDoc(sourceAssetId = it.sourceAssetId) }

    // --- Mask stack -----------------------------------------------------------------------

    /** Histogram or vectorscope over the image (a view setting, not part of the edit). */
    var scope by mutableStateOf(ScopeKind.Off)

    /** The mask layer being edited (index into the stack). */
    var maskIndex by mutableIntStateOf(0)

    val currentMaskLayer: MaskLayer? get() = doc.maskLayers.getOrNull(maskIndex)

    /** Changes the current mask layer's mask (an older single mask is moved into the stack first). */
    fun editMask(key: Any? = null, f: (Mask) -> Mask) = editMaskLayer(key) { it.copy(mask = f(it.mask)) }

    fun editMaskLayer(key: Any? = null, f: (MaskLayer) -> MaskLayer) = edit(key) { d ->
        val s = d.withMaskStack()
        val i = maskIndex.coerceIn(0, (s.masks.size - 1).coerceAtLeast(0))
        if (s.masks.isEmpty()) s else s.copy(masks = s.masks.mapIndexed { j, l -> if (j == i) f(l) else l })
    }

    /** A new mask layer on top of the stack; it becomes the one being edited. */
    fun addMask(kind: MaskKind) {
        edit { d -> d.withMaskStack().let { s -> s.copy(masks = s.masks + MaskLayer(Mask(kind = kind, feather = if (kind == MaskKind.Luminance) 0.1f else 0.02f))) } }
        maskIndex = doc.masks.lastIndex
    }

    fun removeMask() {
        val i = maskIndex
        edit { d -> d.withMaskStack().let { s -> s.copy(masks = s.masks.filterIndexed { j, _ -> j != i }) } }
        maskIndex = (i - 1).coerceAtLeast(0)
    }

    companion object {
        private const val MAX_UNDO = 100
        const val PREVIEW_PX = 1400
    }
}

/** Decodes a Library image, downsampled so its long side is at most [maxPx] (full size with Int.MAX_VALUE). Off the main thread only. */
fun decodeAsset(store: ProjectStore, record: AssetRecord, maxPx: Int): Bitmap? {
    val file = store.fileOf(record)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val long = maxOf(bounds.outWidth, bounds.outHeight)
    // Power-of-two subsampling while decoding (cheap), then an exact scale for the rest.
    val sample = dk.cbavf.studio.library.AssetDecode.sampleSize(long, maxPx)
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val decodedLong = maxOf(decoded.width, decoded.height)
    if (decodedLong <= maxPx) return decoded
    val k = maxPx.toFloat() / decodedLong
    return Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt().coerceAtLeast(1), (decoded.height * k).toInt().coerceAtLeast(1), true)
        .also { if (it !== decoded) decoded.recycle() }
}

/** The imported .cube of an edit, if any (null if the file is gone or unreadable). Reads a file: call it off the main thread. */
fun loadCube(store: ProjectStore, doc: DevelopDoc): Lut? =
    doc.lutFile?.let { runCatching { Lut.parseCube(store.file(it).readText()) }.getOrNull() }

/**
 * Renders the edit at full resolution and files it as a child of the source: PNG for PNG sources
 * (line art keeps every pixel), JPEG otherwise. Exported to Pictures/Studio.
 */
fun saveDevelop(context: Context, store: ProjectStore, lineage: Lineage, doc: DevelopDoc): AssetRecord {
    val source = lineage[doc.sourceAssetId ?: error("No image")] ?: error("Image not in Library")
    val bitmap = decodeAsset(store, source, Int.MAX_VALUE) ?: error("Could not read image")
    val style = doc.styleAssetId?.let { lineage[it] }?.let { decodeAsset(store, it, maxOf(bitmap.width, bitmap.height)) }
    val result = renderDevelop(bitmap, doc, Lut.build(doc), loadCube(store, doc), style)
    bitmap.recycle()
    style?.recycle()
    val png = source.file.endsWith(".png")
    val ext = if (png) "png" else "jpg"
    val tmp = File(store.tempDir, "develop.$ext")
    tmp.outputStream().buffered().use {
        result.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, it)
    }
    val w = result.width
    val h = result.height
    result.recycle()
    val parents = listOfNotNull(source.id, doc.styleAssetId?.takeIf { doc.styleAmount > 0f })
    val record = store.addAssetFile(tmp, ext, parents, "develop.render", Build.MODEL, w, h, Recipe(develop = doc))
    exportToMedia(context, "studio-${record.id}.$ext", if (png) "image/png" else "image/jpeg") { out ->
        store.fileOf(record).inputStream().use { it.copyTo(out) }
    }?.let { store.update(record.copy(exportedUri = it.toString())) }
    return record
}
