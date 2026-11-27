package eu.chezmaurice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import eu.chezmaurice.ui.generated.resources.Res
import eu.chezmaurice.ui.generated.resources.young_serif_regular
import org.jetbrains.compose.resources.Font

/**
 * Semantic colour tokens derived from a named palette — `MauriceTheme.swift`
 * in Maurice, `CarnetTheme.swift` in Carnet: the two apps share the palettes.
 */
@Immutable
data class MauriceTheme(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkMute: Color,
    val rule: Color,
    val ruleHard: Color,
    val codeBg: Color,
    val codeInk: Color,
    /** Carnet: the faintest ink, for hints and empty heatmap cells. */
    val inkHint: Color,
    /** Carnet: a wash of ink behind selected or pressed things. */
    val inkWash: Color,
    /** Carnet: the one colour that says "good". */
    val good: Color,
)

/** A palette defines three surface colours; ink and rule tokens follow from whether it is dark. */
data class Palette(
    val id: String,
    val label: String,
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
) {
    fun resolve(): MauriceTheme {
        val ink = if (isDark) Color(0xFFF0EBE2) else Color(0xFF262320)
        return MauriceTheme(
            isDark = isDark,
            bg = bg,
            surface = surface,
            surfaceAlt = surfaceAlt,
            ink = ink,
            inkSoft = ink.copy(alpha = if (isDark) 0.65f else 0.62f),
            inkMute = ink.copy(alpha = if (isDark) 0.40f else 0.42f),
            rule = ink.copy(alpha = 0.10f),
            ruleHard = ink.copy(alpha = 0.18f),
            codeBg = surfaceAlt,
            codeInk = ink.copy(alpha = 0.85f),
            inkHint = ink.copy(alpha = if (isDark) 0.28f else 0.30f),
            inkWash = ink.copy(alpha = if (isDark) 0.13f else 0.09f),
            good = if (isDark) Color(0xFF8AA88A) else Color(0xFF5A735A),
        )
    }
}

object Palettes {
    private fun c(r: Int, g: Int, b: Int) = Color(r, g, b)

    val light = listOf(
        Palette("white", "White", false, c(0xff, 0xff, 0xff), c(0xff, 0xff, 0xff), c(0xf6, 0xf6, 0xf4)),
        Palette("bone", "Bone", false, c(0xfa, 0xfa, 0xf8), c(0xff, 0xff, 0xff), c(0xf0, 0xf0, 0xec)),
        Palette("ivory", "Ivory", false, c(0xf8, 0xf5, 0xef), c(0xfe, 0xfc, 0xf7), c(0xef, 0xea, 0xe0)),
        Palette("cream", "Cream", false, c(0xf5, 0xef, 0xe6), c(0xfb, 0xf7, 0xf0), c(0xf0, 0xe9, 0xdc)),
        Palette("stone", "Stone", false, c(0xef, 0xec, 0xe6), c(0xf8, 0xf6, 0xf1), c(0xe6, 0xe2, 0xd9)),
        Palette("sage", "Sage", false, c(0xec, 0xef, 0xe7), c(0xf5, 0xf7, 0xf0), c(0xe2, 0xe7, 0xd9)),
        Palette("mist", "Mist", false, c(0xec, 0xef, 0xf3), c(0xf6, 0xf8, 0xfb), c(0xdd, 0xe3, 0xea)),
    )

    val dark = listOf(
        Palette("studio", "Studio", true, c(0x1f, 0x1c, 0x19), c(0x26, 0x23, 0x20), c(0x18, 0x16, 0x14)),
        Palette("slate", "Slate", true, c(0x17, 0x1a, 0x1d), c(0x1f, 0x23, 0x28), c(0x0f, 0x12, 0x16)),
        Palette("forest", "Forest", true, c(0x14, 0x18, 0x15), c(0x1c, 0x21, 0x1c), c(0x0e, 0x12, 0x0e)),
        Palette("plum", "Plum", true, c(0x1c, 0x17, 0x1c), c(0x24, 0x1e, 0x26), c(0x13, 0x0f, 0x18)),
        Palette("espresso", "Espresso", true, c(0x1a, 0x19, 0x16), c(0x22, 0x20, 0x19), c(0x10, 0x0f, 0x0c)),
    )

    val all = light + dark

    /** Cream is the fallback, as on iOS. */
    fun named(id: String): MauriceTheme = (all.firstOrNull { it.id == id } ?: light[3]).resolve()
}

val LocalMauriceTheme = staticCompositionLocalOf { Palettes.named("cream") }

/** Young Serif — the "Chez Maurice" wordmark face. */
@Composable
fun wordmarkFamily(): FontFamily = FontFamily(Font(Res.font.young_serif_regular))

@Composable
fun wordmark(size: TextUnit): TextStyle = TextStyle(fontFamily = wordmarkFamily(), fontSize = size)

/**
 * The app theme. `paletteId` "auto" follows the system: cream by day,
 * studio by night, as on iOS.
 */
@Composable
fun MauriceThemeProvider(paletteId: String = "auto", content: @Composable () -> Unit) {
    val theme = if (paletteId == "auto") {
        Palettes.named(if (isSystemInDarkTheme()) "studio" else "cream")
    } else {
        Palettes.named(paletteId)
    }
    val scheme = if (theme.isDark) {
        darkColorScheme(
            background = theme.bg, surface = theme.surface, surfaceVariant = theme.surfaceAlt,
            onBackground = theme.ink, onSurface = theme.ink, onSurfaceVariant = theme.inkSoft,
            primary = theme.ink, onPrimary = theme.bg, outline = theme.ruleHard, outlineVariant = theme.rule,
        )
    } else {
        lightColorScheme(
            background = theme.bg, surface = theme.surface, surfaceVariant = theme.surfaceAlt,
            onBackground = theme.ink, onSurface = theme.ink, onSurfaceVariant = theme.inkSoft,
            primary = theme.ink, onPrimary = theme.bg, outline = theme.ruleHard, outlineVariant = theme.rule,
        )
    }
    CompositionLocalProvider(LocalMauriceTheme provides theme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
