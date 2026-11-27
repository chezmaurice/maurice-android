package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.CalibreApi
import kotlin.test.Test

class ReadingArticlesParity {
    @Test fun readingArticles() = Parity.check("carnet", "reading-articles") { c ->
        val calibre = CalibreApi(ReadingFixture.api())
        ReadingObs.exchange {
            when (c.string("op")) {
                "article" -> calibre.article(c.string("slug")!!, c.string("locale")!!).let { a ->
                    ReadingObs.article(a) + ("sharedBy" to a.sharedBy?.let { mapOf("username" to it.username, "displayName" to it.displayName) })
                }
                "text" -> calibre.articleText(c.string("slug")!!, c.string("locale")!!).let { t ->
                    mapOf("text" to t.text, "title" to t.title, "styles" to t.styles.orEmpty().map { mapOf("start" to it.start, "end" to it.end, "kind" to it.kind) })
                }
                else -> mapOf("unknown op" to c.string("op"))
            }
        }
    }
}
