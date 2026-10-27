package dk.cbavf.studio.plugins

import dk.cbavf.studio.develop.model.Analysis

/**
 * The scopes a Scope plugin can show. A plugin names one in its manifest ([PluginManifest.scope]); the drawing is the
 * studio's own, so a scope plugin is still only data.
 */
object PluginScopes {
    const val RGB_PARADE = "rgb-parade"
    const val WAVEFORM = "waveform"

    /** Every scope, with its name on screen. */
    val ALL = listOf(RGB_PARADE to "RGB parade", WAVEFORM to "Brightness waveform")

    fun label(id: String?): String? = ALL.firstOrNull { it.first == id }?.second

    /** The columns across a trace, and the brightness levels up it. */
    const val COLS = 256
    const val BINS = 128

    /**
     * What [scope] draws for packed ARGB pixels ([w] × [h]): the parade gives three traces (red, green, blue) side by side,
     * the waveform one (brightness). Each trace is [cols] × [bins] counts, as [Analysis.waveform]. Empty for an unknown scope.
     */
    fun traces(scope: String?, px: IntArray, w: Int, h: Int, cols: Int = COLS, bins: Int = BINS): List<IntArray> = when {
        w <= 0 || h <= 0 || px.size < w * h -> emptyList()
        scope == RGB_PARADE -> (0..2).map { Analysis.waveform(px, w, h, cols, bins, it) }
        scope == WAVEFORM -> listOf(Analysis.waveform(px, w, h, cols, bins))
        else -> emptyList()
    }
}
