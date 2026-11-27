package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.ShelfSource
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An article another member gave a copy of names them (`shared_by`, gardenShelf.ts / gardenArticles.ts). */
class SharedCopyTest {
    @Test fun theShelfNamesWhoGaveIt() {
        val s = MauriceJson.decodeFromString<ShelfSource>(
            """{"type":"article","captured":true,"shared_by":{"id":"u1","username":"camille","display_name":"Camille","avatar_color":"#2c5aa0","avatar_url":null}}""",
        )
        assertEquals("Camille", s.sharedBy?.displayName)
    }

    @Test fun aMemberGoneIsAUsernameAlone() {
        val s = MauriceJson.decodeFromString<ShelfSource>("""{"type":"article","captured":true,"shared_by":{"username":"camille"}}""")
        assertEquals("camille", s.sharedBy?.username)
        assertEquals("", s.sharedBy?.displayName)
    }

    @Test fun oneOwnArticleHasNoGiver() {
        val a = MauriceJson.decodeFromString<Article>("""{"slug":"gaza","locale":"en","shared_by":null}""")
        assertNull(a.sharedBy)
    }
}
