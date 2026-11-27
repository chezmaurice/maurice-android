package eu.chezmaurice.parity

import androidx.compose.ui.graphics.Color
import eu.chezmaurice.ui.Palettes
import eu.chezmaurice.ui.hexColor
import kotlin.math.roundToInt
import kotlin.test.Test

class DomainsColoursParity {
    /** A colour as 0–255 sRGB components and alpha. */
    private fun rgba(c: Color): Map<String, Int> = mapOf(
        "r" to (c.red * 255).roundToInt(), "g" to (c.green * 255).roundToInt(),
        "b" to (c.blue * 255).roundToInt(), "a" to (c.alpha * 255).roundToInt(),
    )

    @Test fun domainsColours() = Parity.check("maurice", "domains-colours") { c ->
        c.string("hex")?.let { return@check mapOf("colour" to rgba(hexColor(it))) }
        val t = Palettes.named(c.string("palette").orEmpty())
        mapOf("isDark" to t.isDark, "bg" to rgba(t.bg), "surface" to rgba(t.surface), "surfaceAlt" to rgba(t.surfaceAlt),
            "ink" to rgba(t.ink), "inkSoft" to rgba(t.inkSoft), "inkMute" to rgba(t.inkMute),
            "rule" to rgba(t.rule), "ruleHard" to rgba(t.ruleHard), "codeBg" to rgba(t.codeBg), "codeInk" to rgba(t.codeInk))
    }
}
