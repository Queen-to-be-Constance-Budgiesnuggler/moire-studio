package dk.cbavf.studio.ui

/**
 * The colours of the canvas-like areas (the Node Lab canvas and the rack panels), as ARGB.
 * Material parts follow the theme through the colour scheme; these areas draw by hand, so they
 * take their colours from here.
 */
data class CanvasColors(
    // Node Lab
    val canvas: Long,
    val grid: Long,
    val node: Long,
    val nodeEdge: Long,
    val nodeText: Long,
    val nodeMuted: Long,
    val field: Long,
    val fieldText: Long,
    val select: Long,
    val selectFill: Long,
    /** Lazy connect and reroute lines. */
    val guide: Long,
    /** The knife line of Cut. */
    val cut: Long,
    /** A link being dragged. */
    val drag: Long,
    val socketRim: Long,
    val hud: Long,
    val hudText: Long,
    val frameEdge: Long,
    val frameText: Long,
    /** The Touch Pad strip and its cross lines. */
    val pad: Long,
    val padLine: Long,
    // Rack
    val rackBack: Long,
    val panel: Long,
    val panelEdge: Long,
    val unitEdge: Long,
    /** Printed text on a panel. */
    val silk: Long,
    val screw: Long,
    /** The dark hole of a jack. */
    val hole: Long,
)

object CanvasTheme {
    /** The studio's own look: the dark colours these areas had before themes reached them. */
    val Classic = CanvasColors(
        canvas = 0xFF1D1D1D, grid = 0x22FFFFFF, node = 0xFF303030, nodeEdge = 0xFF151515,
        nodeText = 0xFFDDDDDD, nodeMuted = 0xFFAAAAAA, field = 0xFF545454, fieldText = 0xFFFFFFFF,
        select = 0xFFFFA733, selectFill = 0x33FFA733, guide = 0xFF7FD3FF, cut = 0xFFFF6B6B,
        drag = 0xFFFFFFFF, socketRim = 0xFF111111, hud = 0xCC2B2B2B, hudText = 0xFFFFFFFF,
        frameEdge = 0x55FFFFFF, frameText = 0xDDFFFFFF, pad = 0xFF3A3A3A, padLine = 0x66FFFFFF,
        rackBack = 0xFF101114, panel = 0xFF1E1F22, panelEdge = 0xFF2E3036, unitEdge = 0xFF17181B,
        silk = 0xFFD9D6CC, screw = 0xFF8D8A82, hole = 0xFF0B0B0C,
    )

    /** The canvas colours for [theme]: the studio's own theme keeps its classic look. */
    fun of(theme: StudioTheme): CanvasColors = if (theme == StudioTheme.Studio) Classic else from(theme.p)

    /** Canvas colours made from a theme's palette. */
    fun from(p: Palette): CanvasColors {
        val black = 0xFF000000
        val edge = if (p.dark) mix(p.bg, black, 0.4f) else p.outline
        return CanvasColors(
            canvas = p.bg,
            grid = withAlpha(p.text, 0x22),
            node = p.raised,
            nodeEdge = edge,
            nodeText = p.text,
            nodeMuted = p.muted,
            field = mix(p.raised, p.text, 0.18f),
            fieldText = p.text,
            select = p.accent,
            selectFill = withAlpha(p.accent, 0x33),
            guide = p.second,
            cut = p.error,
            drag = p.text,
            socketRim = if (p.dark) mix(p.bg, black, 0.5f) else p.text,
            hud = withAlpha(p.surface, 0xCC),
            hudText = p.text,
            frameEdge = withAlpha(p.text, 0x55),
            frameText = withAlpha(p.text, 0xDD),
            pad = p.raised,
            padLine = withAlpha(p.text, 0x66),
            rackBack = if (p.dark) mix(p.bg, black, 0.3f) else mix(p.bg, p.outline, 0.25f),
            panel = p.surface,
            panelEdge = p.outline,
            unitEdge = edge,
            silk = p.text,
            screw = p.muted,
            hole = mix(p.bg, black, 0.7f),
        )
    }

    /** [a] moved towards [b] by [t] (0 = a, 1 = b), channel by channel, alpha included. */
    fun mix(a: Long, b: Long, t: Float): Long {
        fun ch(v: Long, shift: Int) = (v shr shift and 0xFF).toFloat()
        var out = 0L
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val c = Math.round(ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toLong().coerceIn(0, 255)
            out = out or (c shl shift)
        }
        return out
    }

    /** [argb] with its alpha set to [alpha] (0..255). */
    fun withAlpha(argb: Long, alpha: Int): Long = (argb and 0xFFFFFF) or (alpha.toLong().coerceIn(0, 255) shl 24)
}
