package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.CalibreApi
import kotlin.test.Test

/** Tying a book entry to a library book (LinkBookSheet.kt); `ShelfLinkBookParity.swift`. */
class ShelfLinkBookParity {
    @Test fun linkBook() = Parity.check("carnet", "shelf-link-book") { c ->
        val session = ShelfFixture.session()
        val garden = ShelfFixture.garden(session)
        val calibre = CalibreApi(session.api()!!)
        ShelfFixture.exchange {
            when (c.string("call")) {
                "books" -> calibre.books().map { mapOf("id" to it.id, "title" to it.title, "author" to it.author, "coverLetter" to it.coverLetter) }
                "link" -> ShelfObserve.written(garden.linkBook(ShelfFixture.entry(c), c.int("bookId") ?: 0))
                else -> "unknown call"
            }
        }
    }
}
