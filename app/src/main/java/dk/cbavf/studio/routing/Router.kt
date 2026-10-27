package dk.cbavf.studio.routing

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import dk.cbavf.studio.library.CanvasDoc
import dk.cbavf.studio.library.renderBitmap
import dk.cbavf.studio.routing.model.Cable
import dk.cbavf.studio.routing.model.Rack
import dk.cbavf.studio.routing.model.RackPreset
import dk.cbavf.studio.routing.model.RackPresets
import dk.cbavf.studio.routing.model.RoutingDoc
import dk.cbavf.studio.ui.Undoable

/** What travels along a cable: a canvas recipe, or pixels. */
sealed interface RouteValue {
    data class Canvas(val doc: CanvasDoc) : RouteValue
    class Image(val bitmap: Bitmap) : RouteValue

    /** A number: a value, a trigger (1 for a frame) or a gate (1 while on). */
    data class Level(val v: Float) : RouteValue

    /** Size of the picture this value stands for (canvases without a size: a portrait screen). */
    val size: Pair<Int, Int>
        get() = when (this) {
            is Canvas -> (doc.widthPx.takeIf { it > 0 } ?: 1080) to (doc.heightPx.takeIf { it > 0 } ?: 1920)
            is Image -> bitmap.width to bitmap.height
            is Level -> 1 to 1
        }
}

/**
 * The studio's live patch bay. Modules [publish] their outputs as they change; inputs read what
 * the cable into them carries. Values and versions are snapshot state, so rooms that read an input
 * recompose when it changes. Bitmaps handed out are shared: never recycle them.
 */
class Router(private val rack: Rack = Rack) {
    val edit = Undoable(RoutingDoc())
    val doc: RoutingDoc get() = edit.doc

    private val values = mutableStateMapOf<String, RouteValue>()
    private val versions = mutableStateMapOf<String, Int>()
    private val rendered = HashMap<String, Pair<Int, Bitmap>>()

    fun publish(output: String, value: RouteValue) {
        values[output] = value
        versions[output] = (versions[output] ?: 0) + 1
    }

    /** Publishes a number on [output]; nothing happens (and nothing recomposes) when it has not changed. */
    fun publishLevel(output: String, v: Float) {
        if ((values[output] as? RouteValue.Level)?.v == v) return
        publish(output, RouteValue.Level(v))
    }

    /** The number arriving at [input], or 0 when nothing is patched into it. */
    fun level(input: String): Float = (inputValue(input) as? RouteValue.Level)?.v ?: 0f

    fun value(output: String): RouteValue? = values[output]
    fun version(output: String): Int = versions[output] ?: 0

    fun inputValue(input: String): RouteValue? = doc.sourceOf(input)?.let { values[it] }

    /** Changes whenever what arrives at [input] changes (or it is repatched); −1 when unpatched. */
    fun inputVersion(input: String): Int = doc.sourceOf(input)?.let { (versions[it] ?: 0) * 31 + it.hashCode() } ?: -1

    /** What [output] carries as pixels, at most [maxPx] on the long side; canvases are rendered (on paper). */
    fun bitmap(output: String, maxPx: Int): Bitmap? {
        val v = values[output] ?: return null
        if (v is RouteValue.Level) return null
        val version = versions[output] ?: 0
        val key = "$output@$maxPx"
        synchronized(rendered) { rendered[key]?.takeIf { it.first == version }?.let { return it.second } }
        val bmp = when (v) {
            is RouteValue.Image -> {
                val b = v.bitmap
                val k = minOf(1f, maxPx.toFloat() / maxOf(b.width, b.height))
                if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
            }
            is RouteValue.Canvas -> {
                val (w, h) = v.size
                val k = minOf(1f, maxPx.toFloat() / maxOf(w, h))
                renderBitmap(v.doc.copy(widthPx = w, heightPx = h), sheet = true, scale = k)
            }
            is RouteValue.Level -> return null
        }
        synchronized(rendered) { rendered[key] = version to bmp }
        return bmp
    }

    fun inputBitmap(input: String, maxPx: Int): Bitmap? = doc.sourceOf(input)?.let { bitmap(it, maxPx) }

    // --- Patching ---------------------------------------------------------------------------

    /** Patches [from] into [to]; false (nothing changed) if the jacks don't fit or it would make a loop. */
    fun connect(from: String, to: String): Boolean {
        val before = doc
        edit.edit { it.connect(from, to, rack) }
        return doc != before
    }

    fun disconnect(input: String) = edit.edit { it.disconnect(input) }

    fun load(d: RoutingDoc) = edit.load(d)

    /** Replaces the whole patching with [p]'s (one undo step); gives back the cables that did not fit and were left out. */
    fun loadPreset(p: RackPreset): List<Cable> {
        val r = RackPresets.load(p, rack)
        edit.edit { r.doc }
        return r.dropped
    }
}
