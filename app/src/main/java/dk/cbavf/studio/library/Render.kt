package dk.cbavf.studio.library

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dk.cbavf.studio.draw.drawLayers
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Renders a canvas recipe to PNG with the same code that draws the screen, at the same pixel size,
 * so the saved image matches what was on screen (screen-exact mode).
 */
fun renderPng(doc: CanvasDoc): ByteArray = encodePng(renderBitmap(doc))

fun renderBitmap(doc: CanvasDoc, sheet: Boolean = true, scale: Float = 1f): Bitmap {
    val image = ImageBitmap((doc.widthPx * scale).toInt().coerceAtLeast(1), (doc.heightPx * scale).toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(image),
        size = Size(doc.widthPx.toFloat(), doc.heightPx.toFloat()),
    ) {
        // A preview draws the full-size document scaled down in one pass (no full-size bitmap).
        scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            // Offscreen: wait for every image, never draw a placeholder into a saved file.
            drawLayers(doc.layers, sheet = sheet, images = dk.cbavf.studio.draw.LayerImages::blocking)
        }
    }
    return image.asAndroidBitmap()
}

fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
}

/** Copies a PNG into Pictures/Studio so Gallery, Notes and other apps can see it. No permission needed. */
fun exportToPictures(context: Context, name: String, png: ByteArray): Uri? =
    exportToMedia(context, "$name.png", "image/png") { it.write(png) }

/** Adds a file to the shared media store: videos under Movies/Studio, everything else under Pictures/Studio. */
fun exportToMedia(context: Context, displayName: String, mime: String, write: (OutputStream) -> Unit): Uri? {
    val video = mime.startsWith("video/")
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Studio" else "Pictures/Studio")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val resolver = context.contentResolver
    val uri = resolver.insert(collection, values) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use(write) ?: error("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }.getOrElse {
        resolver.delete(uri, null, null)
        null
    }
}

/**
 * Files a computed image as a new asset (PNG, or JPEG for photos) with its recipe, and exports
 * it to Pictures/Studio. For the labs, whose results are made in memory.
 */
fun saveBitmapAsset(
    context: Context,
    store: ProjectStore,
    bitmap: Bitmap,
    parents: List<String>,
    operation: String,
    recipe: Recipe,
    png: Boolean,
): AssetRecord {
    val ext = if (png) "png" else "jpg"
    val tmp = java.io.File(store.tempDir, "lab-${System.nanoTime()}.$ext")
    tmp.outputStream().buffered().use {
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, it)
    }
    val record = store.addAssetFile(tmp, ext, parents, operation, android.os.Build.MODEL, bitmap.width, bitmap.height, recipe)
    exportToMedia(context, "studio-${record.id}.$ext", if (png) "image/png" else "image/jpeg") { out ->
        store.fileOf(record).inputStream().use { it.copyTo(out) }
    }?.let { store.update(record.copy(exportedUri = it.toString())) }
    return record
}
