package dk.cbavf.studio.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.develop.model.DevelopDoc
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
class DevelopState {
    var doc by mutableStateOf(DevelopDoc())
        private set
    var tab by mutableStateOf(DevelopTab.Light)
    var presets by mutableStateOf<List<Preset>>(emptyList())
    var canUndo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<DevelopDoc>()
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
    }

    fun undo() {
        doc = history.removeLastOrNull() ?: return
        lastKey = null
        canUndo = history.isNotEmpty()
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
        canUndo = false
        lastKey = null
    }

    fun reset() = edit { DevelopDoc(sourceAssetId = it.sourceAssetId) }

    companion object {
        private const val MAX_UNDO = 100
        const val PREVIEW_PX = 1400
    }
}

/** Decodes a Library image, downsampled so its long side is at most [maxPx] (full size with Int.MAX_VALUE). */
fun decodeAsset(store: ProjectStore, record: AssetRecord, maxPx: Int): Bitmap? {
    val file = store.fileOf(record)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val long = maxOf(bounds.outWidth, bounds.outHeight)
    // Power-of-two subsampling while decoding (cheap), then an exact scale for the rest.
    var sample = 1
    while (long / (sample * 2) >= maxPx) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val decodedLong = maxOf(decoded.width, decoded.height)
    if (decodedLong <= maxPx) return decoded
    val k = maxPx.toFloat() / decodedLong
    return Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt().coerceAtLeast(1), (decoded.height * k).toInt().coerceAtLeast(1), true)
        .also { if (it !== decoded) decoded.recycle() }
}

/** The imported .cube of an edit, if any (null if the file is gone or unreadable). */
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
