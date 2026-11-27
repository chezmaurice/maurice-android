package eu.chezmaurice.parity

import eu.chezmaurice.carnet.entry.cardCanBeShared
import eu.chezmaurice.carnet.entry.cardLink
import eu.chezmaurice.carnet.entry.cardText
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test

/** The card handed to the share sheet; `ShelfShareCardParity.swift`. Android hands the sheet one text, the link last (`cardShareText`): the card's text and its link are compared apart, as iOS hands them. */
class ShelfShareCardParity {
    @Test fun shareCard() = Parity.check("carnet", "shelf-share-card") { c ->
        val entry = ShelfFixture.entry(c)
        val card = MauriceJson.decodeFromString(EntryDetail.Shared.serializer(), c.text("card"))
        mapOf(
            "text" to cardText(entry, card),
            "link" to cardLink(entry, card),
            // With no site, a draft may be shared: then only "says something" decides.
            "hasText" to cardCanBeShared(card, hasSite = false),
        )
    }
}
