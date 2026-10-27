package dk.cbavf.studio.studio

import android.content.Context
import android.os.Build
import dk.cbavf.studio.library.AssetRecord
import dk.cbavf.studio.library.Lineage
import dk.cbavf.studio.library.ProjectStore
import dk.cbavf.studio.library.exportToPictures
import dk.cbavf.studio.library.renderPng
import dk.cbavf.studio.library.saveBitmapAsset

/**
 * Runs a notebook entry again from its recipe and files the result as a new entry next to the first run (see [Notebook.plan]).
 * Renders full size and writes files: call it off the main thread. Returns the new entry, with [AssetRecord.rerunOf] set.
 */
fun rerunExperiment(
    context: Context,
    store: ProjectStore,
    lineage: Lineage,
    record: AssetRecord,
    layerPixels: dk.cbavf.studio.compose.LayerPixels,
): AssetRecord {
    val plan = Notebook.plan(record, store.recipe(record)) ?: error("This entry can't be run again here. Open it in its room.")
    val made = when (plan.kind) {
        RerunKind.Draw -> {
            val doc = plan.recipe.canvas!!
            val png = try { renderPng(doc, plan.scale) } catch (e: OutOfMemoryError) { null } ?: error("Too big to make again on this phone")
            val r = store.addAsset(png, plan.parents, record.operation, plan.recipe, Build.MODEL, record.widthPx, record.heightPx)
            exportToPictures(context, "studio-${r.id}", png)?.let { uri -> store.updateMany(setOf(r.id)) { it.copy(exportedUri = uri.toString()) } }
            r
        }
        RerunKind.Compose -> {
            val doc = plan.recipe.compose!!
            val png = dk.cbavf.studio.compose.composeOutputIsPng(doc, lineage)
            val bitmap = dk.cbavf.studio.compose.renderComposite(doc, 1f, layerPixels)
            try { saveBitmapAsset(context, store, bitmap, plan.parents, record.operation, plan.recipe, png) } finally { bitmap.recycle() }
        }
        // Develop files its own parents (the source, and the style image if it was used): the same as the first run's.
        RerunKind.Develop -> dk.cbavf.studio.develop.saveDevelop(context, store, lineage, plan.recipe.develop!!)
        RerunKind.Glitch -> {
            val doc = plan.recipe.glitch!!
            val source = lineage[doc.sourceAssetId!!] ?: error("The source image is gone")
            val maxPx = plan.maxPx.takeIf { it > 0 } ?: dk.cbavf.studio.glitch.glitchMaxPx(context)
            val src = dk.cbavf.studio.develop.decodeAsset(store, source, maxPx) ?: error("Could not read the source image")
            val palette = dk.cbavf.studio.glitch.decodePalette(store, lineage, doc)
            val out = try { dk.cbavf.studio.glitch.renderGlitch(src, doc, palette) } finally { src.recycle() }
            // JPEG generations are the look: keep them as JPEG, as the first run did.
            try { saveBitmapAsset(context, store, out, plan.parents, record.operation, plan.recipe, png = doc.generations == 0) } finally { out.recycle() }
        }
    }
    store.setRerunOf(made.id, plan.origin)
    return made.copy(rerunOf = plan.origin)
}
