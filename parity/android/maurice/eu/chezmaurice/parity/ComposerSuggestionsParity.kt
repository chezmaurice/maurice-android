package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.EpisodeList
import eu.chezmaurice.core.carnet.SuggestionRows
import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test

class ComposerSuggestionsParity {
    @Test fun composerSuggestions() = Parity.check("maurice", "composer-suggestions") { c ->
        when (c.string("op")) {
            "decode" -> {
                // As SuggestionsApi decodes them (MauriceJson, SuggestionList.suggestions).
                val rows = runCatching { MauriceJson.decodeFromString(SuggestionRows, c.text("rows")) }.getOrNull()
                mapOf("rows" to (rows?.map { r ->
                    mapOf("id" to r.id, "kind" to r.kind, "title" to r.title, "year" to r.year, "subtitle" to r.subtitle,
                        "image" to r.image, "note" to r.note, "state" to r.state, "existing" to r.existing, "isKept" to r.isKept,
                        "candidates" to r.candidates.map { mapOf("id" to it.id, "title" to it.title, "year" to it.year, "subtitle" to it.subtitle) },
                        "entry" to r.entry, "webPath" to r.webPath, "query" to r.query, "searchable" to r.searchable)
                } ?: "undecodable"))
            }
            "episodes" -> {
                val l = runCatching { MauriceJson.decodeFromString(EpisodeList.serializer(), c.text("body")) }.getOrNull()
                    ?: return@check mapOf("episodes" to "undecodable")
                mapOf("feed" to l.feed, "suggested" to l.suggested,
                    "episodes" to l.episodes.map { mapOf("id" to it.id, "title" to it.title, "date" to it.date, "description" to it.description,
                        "image" to it.image, "duration" to it.duration) })
            }
            "open" -> {
                val s = DomainsSupport.Scoped()
                // With its client, as HomeScreen builds it: the drawer's counts go through it.
                val api = MauriceFixture.session().api()!!
                val chat = ChatController(ChatApi(api), s.scope, "u1", client = api)
                chat.loadConversations().join()
                val id = c.string("conversation").orEmpty()
                chat.open(id)
                s.settle(8_000)
                // What SuggestionsMark draws: the row's counts.
                val row = chat.state.value.conversations.firstOrNull { it.id == id }
                val out = mapOf("requests" to DomainsSupport.requests { it.startsWith("/api/suggestions") },
                    "mark" to mapOf("pending" to (row?.suggestions ?: 0), "kept" to (row?.suggestionsKept ?: 0)))
                s.cancel()
                out
            }
            else -> null
        }
    }
}
