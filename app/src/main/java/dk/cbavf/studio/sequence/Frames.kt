package dk.cbavf.studio.sequence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.WindowManager
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.imageAssetIds
import dk.cbavf.studio.library.renderBitmap
import dk.cbavf.studio.sequence.model.AssetSource
import dk.cbavf.studio.sequence.model.CanvasSource
import dk.cbavf.studio.sequence.model.FrameFit
import dk.cbavf.studio.sequence.model.FrameGeometry
import dk.cbavf.studio.sequence.model.SeqItem
import dk.cbavf.studio.sequence.model.SeqSource
import dk.cbavf.studio.sequence.model.SequenceDoc

/** This phone's full screen in pixels, portrait (the projector's frame). */
fun screenSize(context: Context): Pair<Int, Int> {
    val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    return minOf(b.width(), b.height()) to maxOf(b.width(), b.height())
}

/**
 * Turns sequence items into frames. Every frame has the sequence's size (its [SequenceDoc.shape]);
 * each picture is placed by its item's fit and turn. One path serves the projector, the exports
 * and (at a smaller [outScale]) the preview, so what you preview is what you get.
 */
class FrameDecoder(private val store: ProjectStore, private val lineage: Lineage, private val screen: Pair<Int, Int>) {
    fun naturalSize(source: SeqSource): Pair<Int, Int>? = when (source) {
        is AssetSource -> lineage[source.assetId]?.let { it.widthPx to it.heightPx }?.takeIf { it.first > 0 && it.second > 0 }
        is CanvasSource -> (source.doc.widthPx to source.doc.heightPx).takeIf { it.first > 0 && it.second > 0 }
    }

    /** Size of every frame of [doc]; null for an empty timeline. */
    fun frameSize(doc: SequenceDoc): Pair<Int, Int>? {
        if (doc.items.isEmpty()) return null
        return FrameGeometry.frameSize(doc.shape, screen, doc.items.firstNotNullOfOrNull { naturalSize(it.source) })
    }

    /**
     * The frame for [item] at [frameW]×[frameH], scaled by [outScale] (1 = full size). A missing
     * or unreadable picture gives a black frame.
     */
    fun decode(item: SeqItem, frameW: Int, frameH: Int, outScale: Float = 1f): Bitmap {
        val outW = (frameW * outScale).toInt().coerceAtLeast(1)
        val outH = (frameH * outScale).toInt().coerceAtLeast(1)
        val out = black(outW, outH)
        val (natW, natH) = naturalSize(item.source) ?: return out
        val fit = item.effectiveFit
        val p = FrameGeometry.place(natW, natH, item.quarterTurns, frameW, frameH, fit)
        // Decode no bigger than it will be drawn (Library photos can be 12 MP).
        val drawnLong = (maxOf(natW, natH) * p.scale * outScale).toInt().coerceAtLeast(1)
        val picture = when (val s = item.source) {
            is AssetSource -> lineage[s.assetId]?.let { decodeAsset(store, it, maxOf(drawnLong, 64)) }
            is CanvasSource -> renderBitmap(s.doc)
        } ?: return out
        // Picture pixels → frame pixels: centre on the origin, scale (the decode may be smaller
        // than the natural size), turn, then move to the placement centre.
        val k = p.scale * outScale * natW / picture.width
        val m = Matrix().apply {
            postTranslate(-picture.width / 2f, -picture.height / 2f)
            postScale(k, k)
            postRotate(90f * p.quarterTurns)
            postTranslate(p.centerX * outScale, p.centerY * outScale)
        }
        // Exact at full size stays sharp pixel for pixel; anything scaled is filtered.
        val exact = fit == FrameFit.Exact && outScale == 1f && k == 1f
        Canvas(out).drawBitmap(picture, m, Paint().apply { isFilterBitmap = !exact })
        picture.recycle()
        return out
    }

    /** Asset ids a sequence uses: the parents of anything exported from it. */
    fun parentIds(doc: SequenceDoc): List<String> =
        doc.items.flatMap { item ->
            when (val s = item.source) {
                is AssetSource -> listOf(s.assetId)
                // Canvas frames bring the Library images placed on them.
                is CanvasSource -> s.doc.imageAssetIds()
            }
        }.distinct().filter { lineage[it] != null }

    companion object {
        fun black(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    }
}
