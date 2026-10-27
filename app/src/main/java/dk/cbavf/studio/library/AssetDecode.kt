package dk.cbavf.studio.library

/**
 * The arithmetic of reading Library images small: how much to subsample while decoding, and
 * which decodes are small enough to keep in memory for the grids. No Android types, so it is
 * tested on its own; the decoding itself is in ui/AssetImage.kt and develop/DevelopState.kt.
 */
object AssetDecode {
    /** Decodes this big or smaller are kept for the grids (Library, pickers); bigger ones are not. */
    const val CACHE_MAX_PX = 1024

    /**
     * The power-of-two inSampleSize that leaves the long side at least [maxPx] (1 keeps every
     * pixel). An unknown size (0 or less) is read whole.
     */
    fun sampleSize(longSide: Int, maxPx: Int): Int {
        if (longSide <= 0) return 1
        val target = maxPx.coerceAtLeast(1)
        var sample = 1
        while (sample < (1 shl 30) && longSide / (sample * 2) >= target) sample *= 2
        return sample
    }

    fun cacheable(maxPx: Int): Boolean = maxPx in 1..CACHE_MAX_PX

    /** One entry per file and size: a grid at two sizes keeps two. */
    fun cacheKey(path: String, maxPx: Int): String = "$maxPx|$path"

    /** The picture for [key]: from [cached] if it is there, else [load]ed, and [keep]t when it is grid-sized. */
    fun <T : Any> cachedOrLoad(key: String, maxPx: Int, cached: (String) -> T?, load: () -> T?, keep: (String, T) -> Unit): T? =
        cached(key) ?: load()?.also { if (cacheable(maxPx)) keep(key, it) }
}
