package eu.chezmaurice.core.chat

import eu.chezmaurice.core.net.ApiClient
import io.ktor.http.decodeURLPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The member's word on a person fiche, in the app — `PersonReviewView.swift`.
//
// The mail pass writes what it read about a person into their fiche, pending
// until the member says it holds. Nothing is decided here: every gesture goes
// to the server (`POST /api/people/:locale/:basename/review`), which writes
// the fiche as markdown in the garden.

/**
 * A person fiche, by where it lives: `people/<locale>/<basename>.md`, and the
 * element to scroll to (`relation`, `identity-2`, `fragment-003`, `review`).
 */
data class PersonRef(val locale: String, val basename: String, val anchor: String? = null) {
    val id: String get() = "$locale/$basename"

    companion object {
        /**
         * A link into the web garden's fiche page —
         * `/g/<member>/<locale>/fiches/people/<basename>#anchor`, absolute or
         * not — or null. The caller has checked the host is the household's.
         */
        fun fromGardenUrl(url: String): PersonRef? {
            val hash = url.indexOf('#')
            val fragment = if (hash >= 0) url.substring(hash + 1) else null
            var path = if (hash >= 0) url.substring(0, hash) else url
            path = path.substringBefore('?')
            val scheme = path.indexOf("://")
            if (scheme >= 0) path = path.substring(scheme + 3).let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } }
            val parts = path.split('/').filter { it.isNotEmpty() }.map { runCatching { it.decodeURLPart() }.getOrDefault(it) }
            if (parts.size < 6 || parts[0] != "g" || parts[3] != "fiches" || parts[4] != "people") return null
            val base = if (parts[5].endsWith("-fiche")) parts[5] else "${parts[5]}-fiche"
            return PersonRef(parts[2], base, fragment?.let { runCatching { it.decodeURLPart() }.getOrDefault(it) })
        }
    }
}

/** The fiche, as the server describes it (services/personReview.ts, personView). */
@Serializable
data class PersonReviewModel(
    val title: String,
    val locale: String,
    val basename: String,
    val status: String,
    val byMaurice: Boolean,
    val relation: Relation,
    val identities: List<Identity>,
    val fragments: List<Fragment>,
    val exchanges: String? = null,
    /** Everything still pending — what the footer lists for this fiche. */
    val pending: Int,
) {
    @Serializable
    data class Relation(
        val text: String? = null,
        val status: String? = null,
        val since: String? = null,
        val until: String? = null,
        val edited: Boolean? = null,
        /** Maurice's revision of a confirmed relation, once more mail about the person was read. */
        val proposed: Proposed? = null,
    )

    @Serializable
    data class Proposed(val text: String, val since: String? = null, val until: String? = null)

    @Serializable
    data class Identity(
        val address: String,
        val mailboxes: List<String>,
        val status: String,
        val source: String,
        val conflict: String? = null,
    )

    @Serializable
    data class Fragment(
        val id: String,
        val summary: String,
        val status: String,
        val origin: String? = null,
        val address: String? = null,
        val mailbox: String? = null,
        val body: String,
        val edited: Boolean? = null,
    )
}

@Serializable
private data class ReviewResponse(val ok: Boolean, val view: PersonReviewModel? = null)

/** The two routes of a person fiche. */
class PeopleApi(private val api: ApiClient) {
    suspend fun load(ref: PersonRef): PersonReviewModel = api.get("/api/people/${ref.locale}/${ref.basename}")

    /**
     * One gesture — [target] `relation`, `proposal`, `identity`, `fragment`,
     * `fiche` or `all`; [action] `confirm`, `reject` or `edit`. The fiche as it
     * stands after it, or null when the fiche itself went.
     */
    suspend fun review(ref: PersonRef, target: String, action: String, id: String? = null, text: String? = null): PersonReviewModel? {
        val body = buildJsonObject {
            put("target", target)
            put("action", action)
            if (id != null) put("id", id)
            if (text != null) put("text", text)
        }
        return api.post<ReviewResponse>("/api/people/${ref.locale}/${ref.basename}/review", body).view
    }
}

private const val WS = "[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]"
private const val SOURCE = "\\[(?:\\\\.|[^\\]])*\\]\\(maurice-mail:[^)]*\\)"
private val SOURCES = Regex("$WS+—$WS+$SOURCE(?:$WS*;$WS*$SOURCE)*")

/**
 * A line's prose without the pointers the mail pass writes after it —
 * ` — [date, sender, « subject » · box](maurice-mail:…) ; […](…)` — so the
 * sentence reads alone. The file keeps them; the screen shows them on demand.
 */
fun withoutSources(text: String): String = text.replace(SOURCES, "")
