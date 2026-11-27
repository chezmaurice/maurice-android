package eu.chezmaurice.parity

import kotlin.test.Test

/** An entry's page on the garden website, opened signed in (EntryScreen's MoreMenu); `ShelfLinksParity.swift`. */
class ShelfLinksParity {
    @Test fun webLink() = Parity.check("carnet", "shelf-web-link") { c ->
        val session = ShelfFixture.session()
        val opened = (1..(c.int("times") ?: 1)).map {
            try { session.signedInUrl(c.string("path") ?: "")?.replace(Parity.baseUrl, "{base}") } catch (e: Throwable) { mapOf("failed" to ShelfFixture.failure(e)) }
        }
        mapOf("opened" to opened, "requests" to ShelfObserve.requests())
    }
}
