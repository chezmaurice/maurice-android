package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.net.ApiClient
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An entry of the member's garden a résonance can go to (`GET /garden/links/targets`). */
@Serializable
data class LinkTarget(
    val collection: String,
    val locale: String,
    val slug: String,
    val title: String = "",
    val date: String = "",
    /** The basename a [[wiki-link]] to it uses (its fiche when it has one). */
    @SerialName("link_basename") val linkBasename: String = "",
    @SerialName("has_fiche") val hasFiche: Boolean = false,
)

@Serializable private data class LinkTargets(val targets: List<LinkTarget> = emptyList())

/**
 * Where a résonance comes from: what is being read. [basename] when it lives
 * in the garden (an article's fiche); [bookTitle] for a book of the library,
 * which the server matches to its fiche, else keeps as a label.
 */
data class ResonanceSource(val label: String, val basename: String? = null, val bookTitle: String? = null)

/**
 * Résonances (`ResonanceViews.swift`, `data-api/routes/garden-links.ts`): a
 * passage or a thought filed on another entry's fiche, as a dated block with
 * a [[wiki-link]] back to what was being read.
 */
class ResonancesApi(private val api: ApiClient) {
    private val base = "/api/v1/garden/links"

    /** The garden's entries that match [query] (all of them, recent first, when it is empty). */
    suspend fun targets(query: String): List<LinkTarget> =
        api.get<LinkTargets>("$base/targets?q=${query.encodeURLParameter()}").targets

    /** File [comment] and/or [quote] on [to]'s fiche, with a link back to [source]. One of the two is needed. */
    suspend fun create(to: LinkTarget, comment: String, quote: String? = null, source: ResonanceSource? = null) {
        // Whatever a 2xx carries is fine: nothing is read back.
        api.send(HttpMethod.Post, base, buildJsonObject {
            put("to", buildJsonObject { put("locale", to.locale); put("slug", to.slug); put("collection", to.collection) })
            if (comment.isNotEmpty()) put("comment", comment)
            if (!quote.isNullOrEmpty()) put("quote", quote)
            when {
                source == null -> {}
                // A book goes by its title (the server finds its fiche), even with a basename.
                source.bookTitle != null -> put("source_book_title", source.bookTitle)
                else -> put("source", buildJsonObject { put("label", source.label); source.basename?.let { put("basename", it) } })
            }
        })
    }
}
