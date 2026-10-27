package dk.cbavf.studio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
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
import dk.cbavf.studio.library.AssetDecode
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Loads an asset off the main thread, downsampled to at most [maxPx] on its long side.
 * Videos show their first frame; GIFs their first frame. Grid-sized images are kept in memory,
 * so scrolling back shows them at once instead of reading the file again.
 */
@Composable
fun AssetImage(file: File, maxPx: Int, modifier: Modifier, filter: FilterQuality = FilterQuality.Low) {
    val key = AssetDecode.cacheKey(file.path, maxPx)
    val image by produceState(AssetThumbs.get(key), key) {
        // Another file or size: the old picture goes at once, never shown in place of the new one.
        value = AssetThumbs.get(key)
        if (value == null) {
            value = withContext(AssetThumbs.decoder) {
                AssetDecode.cachedOrLoad(key, maxPx, AssetThumbs::get, { decodeSampled(file, maxPx)?.asImageBitmap() }, AssetThumbs::put)
            }
        }
    }
    val bitmap = image
    if (bitmap == null) {
        Box(modifier)
    } else {
        Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, filterQuality = filter)
    }
}

/** Grid images by file and size, and the few threads that decode them (a fast scroll queues instead of decoding dozens at once). */
private object AssetThumbs {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    val decoder = Executors.newFixedThreadPool(3) { Thread(it, "asset-images").apply { isDaemon = true } }.asCoroutineDispatcher()

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, image: ImageBitmap) {
        cache.put(key, image)
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
    val sample = AssetDecode.sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxPx)
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
