package dk.cbavf.studio.glitch.model

/**
 * Keeps the value [load] gave for the last key, so a render loop that asks again for the same key
 * (the colour image, say) does not read it again. A null key gives null without loading.
 * A null result is kept too: a missing image is not looked for on every render.
 */
class LastLoaded<K : Any, V : Any>(private val load: (K) -> V?) {
    private var key: K? = null
    private var value: V? = null

    @Synchronized
    operator fun get(k: K?): V? {
        if (k == null) return null
        if (k != key) {
            value = load(k)
            key = k
        }
        return value
    }
}
