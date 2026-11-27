package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.CalibreApi
import kotlin.test.Test

class ReadingHighlightsParity {
    @Test fun readingHighlights() = Parity.check("carnet", "reading-highlights") { c ->
        val calibre = CalibreApi(ReadingFixture.api())
        val book = c.int("bookId") ?: 7
        ReadingObs.exchange {
            when (c.string("op")) {
                "book.list" -> calibre.highlights(book).map(ReadingObs::highlight)
                "book.create" -> ReadingObs.highlight(calibre.createHighlight(
                    book, c.string("slug")!!, c.string("quote")!!, c.string("color")!!, c.string("note"), c.int("start")!!, c.int("end")!!, c.string("view")!!))
                "book.update" -> ReadingObs.highlight(calibre.updateHighlight(book, c.int("id")!!, c.string("note"), c.string("color")!!))
                "book.delete" -> { calibre.deleteHighlight(book, c.int("id")!!); "ok" }
                "article.list" -> calibre.articleHighlights(c.string("slug")!!, c.string("locale")!!).map(ReadingObs::highlight)
                "article.create" -> ReadingObs.highlight(calibre.createArticleHighlight(
                    c.string("slug")!!, c.string("locale")!!, c.string("quote")!!, c.string("color")!!, c.string("note"), c.int("start")!!, c.int("end")!!, c.string("view")!!))
                "article.update" -> ReadingObs.highlight(calibre.updateArticleHighlight(c.string("slug")!!, c.int("id")!!, c.string("note"), c.string("color")!!))
                "article.delete" -> { calibre.deleteArticleHighlight(c.string("slug")!!, c.int("id")!!); "ok" }
                else -> mapOf("unknown op" to c.string("op"))
            }
        }
    }
}
