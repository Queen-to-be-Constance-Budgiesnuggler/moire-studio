package dk.cbavf.studio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads an asset off the main thread, downsampled to at most [maxPx] on its long side.
 * Videos show their first frame; GIFs their first frame.
 */
@Composable
fun AssetImage(file: File, maxPx: Int, modifier: Modifier, filter: FilterQuality = FilterQuality.Low) {
    val image by produceState<ImageBitmap?>(null, file, maxPx) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, maxPx)?.asImageBitmap() }
    }
    val bitmap = image
    if (bitmap == null) {
        Box(modifier)
    } else {
        Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, filterQuality = filter)
    }
}

fun decodeSampled(file: File, maxPx: Int): Bitmap? {
    if (!file.exists()) return null
    if (file.name.endsWith(".mp4")) {
        val r = MediaMetadataRetriever()
        return runCatching {
            r.setDataSource(file.path)
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx.coerceAtMost(4096), maxPx.coerceAtMost(4096))
        }.getOrNull().also { r.release() }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
