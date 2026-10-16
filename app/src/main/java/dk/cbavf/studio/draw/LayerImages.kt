package dk.cbavf.studio.draw

import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dk.cbavf.studio.develop.decodeAsset
import dk.cbavf.studio.library.ProjectStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Pixels for image layers, by Library asset id. On-screen canvases ask with [forScreen]: a
 * missing image starts loading in the background and the canvas redraws when it arrives.
 * Offscreen renders (Save, Animate frames, Sequence, Loop) use [blocking], so they never miss one.
 */
object LayerImages {
    @Volatile
    var store: ProjectStore? = null

    /** Images are kept at most this big on their long side; layers scale them back to full size. */
    private const val MAX_PX = 2048

    private val cache = object : LruCache<String, ImageBitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "layer-images").apply { isDaemon = true } }

    /** Bumped whenever an image finishes loading; canvases read it so they redraw. */
    val version = mutableIntStateOf(0)

    fun forScreen(assetId: String): ImageBitmap? {
        version.intValue // subscribe the drawing to loads
        cache.get(assetId)?.let { return it }
        if (assetId !in missing && loading.add(assetId)) {
            loader.execute {
                load(assetId)
                loading.remove(assetId)
                version.intValue++
            }
        }
        return null
    }

    fun blocking(assetId: String): ImageBitmap? = cache.get(assetId) ?: load(assetId)

    /** True if the asset could not be read (deleted, unsupported format). */
    fun isMissing(assetId: String) = assetId in missing

    private fun load(assetId: String): ImageBitmap? {
        val s = store ?: return null
        val record = s.lineage()[assetId]
        val bitmap = record?.let { runCatching { decodeAsset(s, it, MAX_PX) }.getOrNull() }
        if (bitmap == null) {
            missing += assetId
            return null
        }
        return bitmap.asImageBitmap().also {
            it.prepareToDraw()
            cache.put(assetId, it)
        }
    }
}
