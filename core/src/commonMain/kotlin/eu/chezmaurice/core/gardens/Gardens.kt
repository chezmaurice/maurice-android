package eu.chezmaurice.core.gardens

import eu.chezmaurice.core.net.ApiClient
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Shared gardens — `GardensStore` in ScopePickerView.swift. Sharing is per
// note, and a shared garden is the set of notes one shares with the same
// people: named by the people, never by an invented title. The sidebar lists
// the member's own garden first, then the shared sets.

@Serializable
data class GardenMember(
    @SerialName("member_id") val memberId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_color") val avatarColor: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GardenNote(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("owner_username") val ownerUsername: String,
    val slug: String,
    val locale: String,
    val title: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("web_path") val webPath: String,
    /** note | fiche | card. */
    val kind: String? = null,
    /** The resource collection of a fiche or a card; null for a note. */
    val collection: String? = null,
    /** Written by Maurice at a domain's adoption and not reviewed yet. */
    val unreviewed: Boolean? = null,
) {
    val isUnreviewed: Boolean get() = unreviewed == true
    /** Kind is part of the identity: a note and a card can hold the same slug. */
    val id: String get() = "$ownerId/${kind ?: "note"}/$slug"
    /** Only notes can be shared today: only they open the access sheet. */
    val isNote: Boolean get() = (kind ?: "note") == "note"
}

@Serializable
data class Garden(
    val id: String,
    val mine: Boolean,
    val members: List<GardenMember>,
    @SerialName("web_theme") val webTheme: String,
    @SerialName("web_path") val webPath: String,
    val notes: List<GardenNote>,
)

@Serializable
data class NoteAccessMember(
    @SerialName("member_id") val memberId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_color") val avatarColor: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val tends: Boolean,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("is_self") val isSelf: Boolean,
)

@Serializable
data class NoteAccess(
    @SerialName("owner_id") val ownerId: String,
    val slug: String,
    val title: String,
    val members: List<NoteAccessMember>,
)

@Serializable
private data class GardensResponse(val gardens: List<Garden>)

@Serializable
private data class WebThemesResponse(val themes: List<Theme>) {
    @Serializable
    data class Theme(val id: String, val name: String)
}

data class GardensState(
    val gardens: List<Garden> = emptyList(),
    val loaded: Boolean = false,
    /** Which garden's page fills the main pane (null → the chat). */
    val openGardenId: String? = null,
) {
    fun garden(id: String?): Garden? = gardens.firstOrNull { it.id == id }
}

/** How the garden page orders its entries: newest edit (the server's order), title, kind. */
enum class GardenSort(val raw: String, val labelKey: String) {
    Recent("recent", "sort.recent"), Title("title", "sort.title"), Kind("kind", "sort.kind");

    companion object {
        fun of(raw: String?): GardenSort = entries.firstOrNull { it.raw == raw } ?: Recent
    }
}

class GardensStore(private val api: ApiClient) {
    private val _state = MutableStateFlow(GardensState())
    val state: StateFlow<GardensState> = _state.asStateFlow()

    fun open(id: String?) = _state.update { it.copy(openGardenId = id) }

    suspend fun load() {
        val resp = quietly { api.get<GardensResponse>("/api/v1/gardens") } ?: return
        _state.update { it.copy(gardens = resp.gardens, loaded = true) }
    }

    /** A shared garden's display theme (the member's own follows their web-theme preference, set by the caller). */
    suspend fun setTheme(themeId: String, garden: Garden) {
        quietly { api.send(HttpMethod.Patch, "/api/v1/gardens/${pathEncoded(garden.id)}", buildJsonObject { put("web_theme", themeId) }) }
        load()
    }

    suspend fun noteAccess(note: GardenNote): NoteAccess? =
        quietly { api.get<NoteAccess>("/api/v1/gardens/note/${note.ownerId}/${note.slug}/access") }

    suspend fun share(note: GardenNote, memberId: String) {
        quietly { api.send(HttpMethod.Post, "/api/v1/gardens/note/${note.ownerId}/${note.slug}/share", buildJsonObject { put("member_id", memberId) }) }
        load()
    }

    suspend fun leave(note: GardenNote) {
        quietly { api.send(HttpMethod.Post, "/api/v1/gardens/note/${note.ownerId}/${note.slug}/leave", buildJsonObject {}) }
        load()
    }

    /** The site's themes, `id` to name; "Default" alone when the server says none. */
    suspend fun webThemes(): List<Pair<String, String>> =
        quietly { api.get<WebThemesResponse>("/api/web-themes") }?.themes?.takeIf { it.isNotEmpty() }?.map { it.id to it.name }
            ?: listOf("default" to "Default")

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    companion object {
        /**
         * People-named: "My garden", "You & Nadia", "The whole house" — the
         * last when the household has more than two members (guests aside)
         * and all of them tend it.
         */
        fun label(garden: Garden, me: String?, householdSize: Int, strings: (String) -> String): String {
            if (garden.mine) return strings("gardens.mine")
            val others = garden.members.filter { it.memberId != me }
            if (householdSize > 2 && garden.members.size >= householdSize) return strings("gardens.whole_house")
            val parts = listOf(strings("gardens.you")) + others.map { it.displayName }
            if (parts.size <= 1) return parts.joinToString("")
            return parts.dropLast(1).joinToString(", ") + " & " + parts.last()
        }

        /** Everyone but me — the faces a shared set is recognised by. */
        fun others(garden: Garden, me: String?): List<GardenMember> = garden.members.filter { it.memberId != me }

        /** The entries the page lists: matching [query] (title or slug), in [sort]'s order. */
        fun filtered(notes: List<GardenNote>, query: String, sort: GardenSort): List<GardenNote> {
            val q = query.trim().lowercase()
            val matching = if (q.isEmpty()) notes else notes.filter { it.title.lowercase().contains(q) || it.slug.contains(q) }
            val byTitle = Comparator<GardenNote> { a, b ->
                val c = a.title.lowercase().compareTo(b.title.lowercase())
                if (c != 0) c else a.id.compareTo(b.id)
            }
            return when (sort) {
                GardenSort.Recent -> matching
                GardenSort.Title -> matching.sortedWith(byTitle)
                GardenSort.Kind -> matching.sortedWith { a, b ->
                    val (ka, kb) = kindRank(a) to kindRank(b)
                    if (ka != kb) ka.compareTo(kb)
                    else {
                        val (x, y) = (a.updatedAt ?: "") to (b.updatedAt ?: "")
                        if (x == y) byTitle.compare(a, b) else y.compareTo(x)
                    }
                }
            }
        }

        /** Notes, then fiches, then cards — the order a garden is written in. */
        private fun kindRank(n: GardenNote) = when (n.kind ?: "note") { "note" -> 0; "fiche" -> 1; else -> 2 }

        /** As `.urlPathAllowed` leaves it: only what a path cannot hold is escaped. */
        internal fun pathEncoded(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (b >= 0 && (c.isLetterOrDigit() || c in "-._~!$&'()*+,;=:@/")) append(c)
                else append('%').append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
            }
        }
    }
}
