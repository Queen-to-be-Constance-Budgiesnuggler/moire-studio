package dk.cbavf.studio.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A theme's palette, in the roles the app uses: a background and two surface steps, text and
 * muted text, an accent and two companions, an error red. Classic editor themes keep their own
 * published colours.
 */
data class Palette(
    val dark: Boolean,
    val bg: Long,
    val surface: Long,
    val raised: Long,
    val text: Long,
    val muted: Long,
    val accent: Long,
    val second: Long,
    val third: Long,
    val error: Long,
    val outline: Long,
)

/** The selected theme's colours for the hand-drawn areas (Node Lab canvas, rack panels). */
val LocalCanvasColors = staticCompositionLocalOf { CanvasTheme.Classic }

/** The studio's themes: its own, and the editor classics. */
enum class StudioTheme(val label: String, val p: Palette) {
    Studio("Studio (default)", Palette(true, 0xFF141218, 0xFF1D1B20, 0xFF2B2930, 0xFFE6E0E9, 0xFFCAC4D0, 0xFFD0BCFF, 0xFFCCC2DC, 0xFFEFB8C8, 0xFFF2B8B5, 0xFF938F99)),
    GruvboxDark("Gruvbox dark", Palette(true, 0xFF282828, 0xFF32302F, 0xFF3C3836, 0xFFEBDBB2, 0xFFA89984, 0xFFFABD2F, 0xFF8EC07C, 0xFFD3869B, 0xFFFB4934, 0xFF665C54)),
    GruvboxLight("Gruvbox light", Palette(false, 0xFFFBF1C7, 0xFFF2E5BC, 0xFFEBDBB2, 0xFF3C3836, 0xFF7C6F64, 0xFFAF3A03, 0xFF427B58, 0xFF8F3F71, 0xFF9D0006, 0xFFBDAE93)),
    Dracula("Dracula", Palette(true, 0xFF282A36, 0xFF21222C, 0xFF44475A, 0xFFF8F8F2, 0xFF6272A4, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFFF5555, 0xFF6272A4)),
    Nord("Nord", Palette(true, 0xFF2E3440, 0xFF3B4252, 0xFF434C5E, 0xFFECEFF4, 0xFFD8DEE9, 0xFF88C0D0, 0xFF81A1C1, 0xFFB48EAD, 0xFFBF616A, 0xFF4C566A)),
    SolarizedDark("Solarized dark", Palette(true, 0xFF002B36, 0xFF073642, 0xFF0A4351, 0xFF93A1A1, 0xFF839496, 0xFF268BD2, 0xFF2AA198, 0xFFD33682, 0xFFDC322F, 0xFF586E75)),
    SolarizedLight("Solarized light", Palette(false, 0xFFFDF6E3, 0xFFEEE8D5, 0xFFE4DDC8, 0xFF586E75, 0xFF657B83, 0xFF268BD2, 0xFF2AA198, 0xFFD33682, 0xFFDC322F, 0xFF93A1A1)),
    Monokai("Monokai", Palette(true, 0xFF272822, 0xFF1E1F1C, 0xFF3E3D32, 0xFFF8F8F2, 0xFF75715E, 0xFFA6E22E, 0xFFF92672, 0xFF66D9EF, 0xFFF92672, 0xFF75715E)),
    OneDark("One Dark", Palette(true, 0xFF282C34, 0xFF21252B, 0xFF2C313A, 0xFFABB2BF, 0xFF5C6370, 0xFF61AFEF, 0xFF98C379, 0xFFC678DD, 0xFFE06C75, 0xFF3E4451)),
    TokyoNight("Tokyo Night", Palette(true, 0xFF1A1B26, 0xFF16161E, 0xFF292E42, 0xFFC0CAF5, 0xFF565F89, 0xFF7AA2F7, 0xFF7DCFFF, 0xFFBB9AF7, 0xFFF7768E, 0xFF3B4261)),
    CatppuccinMocha("Catppuccin Mocha", Palette(true, 0xFF1E1E2E, 0xFF181825, 0xFF313244, 0xFFCDD6F4, 0xFFA6ADC8, 0xFFCBA6F7, 0xFF89B4FA, 0xFFF5C2E7, 0xFFF38BA8, 0xFF6C7086)),
    CatppuccinLatte("Catppuccin Latte", Palette(false, 0xFFEFF1F5, 0xFFE6E9EF, 0xFFDCE0E8, 0xFF4C4F69, 0xFF6C6F85, 0xFF8839EF, 0xFF1E66F5, 0xFFEA76CB, 0xFFD20F39, 0xFF9CA0B0));

    /** The palette as a Material 3 colour scheme. */
    fun scheme(): ColorScheme {
        val p = p
        fun c(v: Long) = Color(v)
        // Text on an accent: the theme's background reads on light accents, white on dark ones.
        val onAccent = if (luminance(p.accent) > 0.45f) c(p.bg) else Color.White
        return if (p.dark) darkColorScheme(
            primary = c(p.accent), onPrimary = onAccent,
            primaryContainer = c(p.raised), onPrimaryContainer = c(p.accent),
            secondary = c(p.second), onSecondary = c(p.bg),
            secondaryContainer = c(p.raised), onSecondaryContainer = c(p.text),
            tertiary = c(p.third), onTertiary = c(p.bg),
            background = c(p.bg), onBackground = c(p.text),
            surface = c(p.bg), onSurface = c(p.text),
            surfaceVariant = c(p.raised), onSurfaceVariant = c(p.muted),
            surfaceContainer = c(p.surface), surfaceContainerLow = c(p.surface), surfaceContainerHigh = c(p.raised),
            surfaceContainerHighest = c(p.raised), surfaceContainerLowest = c(p.bg),
            error = c(p.error), outline = c(p.outline), outlineVariant = c(p.raised),
        ) else lightColorScheme(
            primary = c(p.accent), onPrimary = onAccent,
            primaryContainer = c(p.raised), onPrimaryContainer = c(p.accent),
            secondary = c(p.second), onSecondary = c(p.bg),
            secondaryContainer = c(p.raised), onSecondaryContainer = c(p.text),
            tertiary = c(p.third), onTertiary = c(p.bg),
            background = c(p.bg), onBackground = c(p.text),
            surface = c(p.bg), onSurface = c(p.text),
            surfaceVariant = c(p.raised), onSurfaceVariant = c(p.muted),
            surfaceContainer = c(p.surface), surfaceContainerLow = c(p.surface), surfaceContainerHigh = c(p.raised),
            surfaceContainerHighest = c(p.raised), surfaceContainerLowest = c(p.bg),
            error = c(p.error), outline = c(p.outline), outlineVariant = c(p.raised),
        )
    }

    companion object {
        fun luminance(argb: Long): Float {
            val r = (argb shr 16 and 0xFF) / 255f
            val g = (argb shr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
    }
}
