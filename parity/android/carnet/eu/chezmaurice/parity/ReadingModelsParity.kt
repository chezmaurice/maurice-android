package eu.chezmaurice.parity

import androidx.compose.ui.graphics.Color
import eu.chezmaurice.carnet.reading.READER_SIZES
import eu.chezmaurice.carnet.reading.ReaderSize
import eu.chezmaurice.carnet.reading.formatSize
import eu.chezmaurice.carnet.reading.highlightColor
import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.Chapter
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.carnet.SummaryStatus
import eu.chezmaurice.core.carnet.gardenImageUrl
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.ui.Palette
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test

class ReadingModelsParity {
    /** On screen: 8 bits a channel. */
    private fun rgba(c: Color) = mapOf(
        "r" to Math.round(c.red * 255), "g" to Math.round(c.green * 255),
        "b" to Math.round(c.blue * 255), "a" to Math.round(c.alpha * 255),
    )
    private fun ink(dark: Boolean) = Palette("p", "p", dark, Color.White, Color.White, Color.White).resolve().ink

    @Test fun readingModels() = Parity.check("carnet", "reading-models") { c ->
        ReadingFixture.session()
        try { observe(c) } catch (e: Throwable) { if (e is AssertionError) throw e; mapOf("thrown" to true) }
    }

    private fun observe(c: ParityCase): Any? =
        when (c.string("kind")) {
            "chapter" -> ReadingObs.chapter(MauriceJson.decodeFromString(Chapter.serializer(), c.text("json")))
            "article" -> ReadingObs.article(MauriceJson.decodeFromString(Article.serializer(), c.text("json")))
            "status" -> mapOf("isComplete" to MauriceJson.decodeFromString(SummaryStatus.serializer(), c.text("json")).isComplete)
            "highlight" -> ReadingObs.highlight(MauriceJson.decodeFromString(Highlight.serializer(), c.text("json")))
            // The route, without the household's origin: the port is the run's.
            "gardenImage" -> gardenImageUrl(Parity.baseUrl, c.string("image"))?.replace(Parity.baseUrl, "")
            "palette" -> c.string("key")!!.let { key ->
                mapOf("light" to rgba(highlightColor(key, ink(false), false)), "dark" to rgba(highlightColor(key, ink(true), true)))
            }
            "fontSizes" -> mapOf(
                "sizes" to READER_SIZES.map { it.toDouble() },
                "default" to ReaderSize.get(),
                "cycle" to listOf(ReaderSize.next(), ReaderSize.next(), ReaderSize.next()),
            )
            "formatSize" -> formatSize(c["bytes"]!!.jsonPrimitive.longOrNull!!)
            else -> mapOf("unknown kind" to c.string("kind"))
        }
}
