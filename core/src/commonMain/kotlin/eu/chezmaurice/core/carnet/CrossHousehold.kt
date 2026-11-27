package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Giving an entry to someone in another household, and inviting a friend as
 * a guest (`CrossHouseholdAPI.swift`) — every network call of it in one
 * place. Design: maurice/specs/cross-household-share.md (sections A–D).
 *
 * The device signed into both households is the courier: the entry is read
 * whole from the household on screen, shown, then written whole into the
 * other with that household's session. No server ever talks to another.
 * [target] is a household of this device as one of its members
 * (`Session.api()` for the one on screen, `Session.apiAs` for another).
 */
class CrossHouseholdApi(private val target: ApiClient) {

    /** What can go wrong, each said in the app's language by the sheet (`GiveMessages.describe`). */
    sealed class Failure(message: String? = null) : Exception(message) {
        /** The other household already holds this entry (same canonical URL / ISBN): where it is. */
        data class Exists(val collection: String, val locale: String, val slug: String) : Failure()
        /** The person chosen can no longer be shared with there. */
        data object Unreachable : Failure()
        /** The household does not answer (no network, the server down). */
        data object Offline : Failure()
        /** The session there was refused: it has been forgotten on the device. */
        data object SessionEnded : Failure()
        /** Its server does not know the route yet. */
        data object Unsupported : Failure()
        /** 413: what was ticked is more than the household takes in one gift. */
        data object TooLarge : Failure()
        /** 403: the member may not do this here. The server's code, if any. */
        data class Forbidden(val code: String?) : Failure(code)
        /** The server refused, and said why. */
        data class Refused(val code: String) : Failure(code)
        class Other(message: String) : Failure(message)
    }

    /** What `POST /api/me/guest-invite` answers: the code, the link the QR carries, when it lapses. */
    data class GuestInvite(val code: String, val link: String?, val expiresAt: String)

    /** Where the copy landed over there. */
    data class Imported(val collection: String, val locale: String, val slug: String) {
        val ref: String get() = "$collection/$locale/$slug"
    }

    // ── The other household's people ────────────────────

    /** `GET /api/users` there, with that household's session: the people the member can reach (a guest gets their contacts only). */
    suspend fun roster(): List<Member> = MauriceJson.decodeFromString(run(HttpMethod.Get, USERS, timeout = 12_000))

    // ── Export (the household on screen) ────────────────

    /** `GET …/entries/:c/:l/:s/export` — the entry, element by element. */
    suspend fun export(collection: String, locale: String, slug: String): EntryExport =
        decoded { EntryExport.of(MauriceJson.parseToJsonElement(run(HttpMethod.Get, entryPath(collection, locale, slug, "/export"), timeout = 30_000)) as JsonObject) }

    /** `GET /api/conversations/:id/export` — a conversation whole; refused unless the member is its only human participant. */
    suspend fun conversation(id: String): ConversationExport =
        decoded { ConversationExport.of(MauriceJson.parseToJsonElement(run(HttpMethod.Get, "/api/conversations/$id/export", timeout = 30_000)) as JsonObject) }

    // ── Import (the other household) ────────────────────

    /** `POST /api/v1/garden/entries/import` there — writes the fiche in the member's notes and shares it with `share_with`. */
    suspend fun import(payload: ImportPayload): Imported = decoded {
        val o = MauriceJson.parseToJsonElement(run(HttpMethod.Post, IMPORT, payload.json(), timeout = 120_000)) as JsonObject
        Imported(o.str("collection")!!, o.str("locale")!!, o.str("slug")!!)
    }

    // ── Guest invite (section A), on the household on screen ──

    /** A single-use invitation (seven days) that makes a guest whose only contact is the member. 403 `not_allowed` for a guest, or a child the admin has not allowed. */
    suspend fun guestInvite(): GuestInvite = decoded {
        val o = MauriceJson.parseToJsonElement(run(HttpMethod.Post, GUEST_INVITE, JsonObject(emptyMap()))) as JsonObject
        GuestInvite(o.str("code")!!, o.str("link"), o.str("expires_at")!!)
    }

    /** `GET /api/me/guest-invites/:code` — `pending`, `used` or `expired`; null when the server cannot be reached or does not know the code. */
    suspend fun guestInviteState(code: String): String? = try {
        (MauriceJson.parseToJsonElement(run(HttpMethod.Get, "/api/me/guest-invites/${code.encodeURLPathPart()}")) as? JsonObject)?.str("state")
    } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }

    // ── Requests ────────────────────────────────────────

    /** Runs a request; a 2xx answers its body, anything else a [Failure]. A 401 also ends that session on the device. */
    private suspend fun run(method: HttpMethod, path: String, body: JsonElement? = null, timeout: Long = 20_000): String {
        val response = try {
            target.http.request(target.url(path)) {
                this.method = method
                target.token?.let { bearerAuth(it) }
                timeout { requestTimeoutMillis = timeout; socketTimeoutMillis = timeout }
                if (body != null) setBody(TextContent(MauriceJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
            }
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { throw Failure.Offline }
        val text = try { response.bodyAsText() } catch (e: CancellationException) { throw e } catch (_: Throwable) { throw Failure.Offline }
        val status = response.status.value
        if (status in 200..299) return text
        if (status == 401) target.onUnauthorized()
        throw failure(status, text)
    }

    private inline fun <T> decoded(read: () -> T): T = try { read() } catch (e: Failure) { throw e } catch (e: CancellationException) { throw e } catch (e: Throwable) {
        throw Failure.Other(e.message ?: "The data couldn't be read.")
    }

    companion object {
        private const val USERS = "/api/users"
        private const val IMPORT = "/api/v1/garden/entries/import"
        private const val GUEST_INVITE = "/api/me/guest-invite"

        /** The spec's /api/garden, mounted at /api/v1/garden. */
        private fun entryPath(c: String, l: String, s: String, suffix: String) = "/api/v1/garden/entries/$c/$l/${s.encodeURLPathPart()}$suffix"

        /** A refusal as the sheet tells it apart, from the status and the server's `{"error"}`. */
        fun failure(status: Int, body: String): Failure {
            val obj = runCatching { MauriceJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val code = obj?.str("error")
            return when {
                status == 401 -> Failure.SessionEnded
                status == 409 && code == "exists" -> {
                    val c = obj!!.str("collection"); val l = obj.str("locale"); val s = obj.str("slug")
                    if (c != null && l != null && s != null) Failure.Exists(c, l, s) else Failure.Refused(code)
                }
                status == 403 -> Failure.Forbidden(code)
                status == 413 -> Failure.TooLarge
                status == 404 && code == "Unknown member" -> Failure.Unreachable
                status == 404 && code == null -> Failure.Unsupported
                status == 502 || status == 503 || status == 504 -> Failure.Offline
                code != null -> Failure.Refused(code)
                // The body as it came (empty when there was none), as iOS shows it.
                else -> Failure.Other(body)
            }
        }
    }
}

// ── Shapes ──────────────────────────────────────────────

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.strings(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    is JsonPrimitive -> if (isString) listOf(content) else emptyList()
    else -> emptyList()
}

/** `GET …/export` (spec B). What is read is kept as it came, so what goes to the other household is untouched. */
data class EntryExport(
    /** `{ collection, locale, slug, title, kind, author, cover?, meta… }`, kept whole. */
    val entry: JsonObject,
    /** `{ kind: "article", text, styles, image? }` or `{ kind: "book", calibre }`. */
    val source: JsonObject?,
    val fiche: Fiche?,
    val highlights: List<Highlight>?,
    val card: Card?,
    val conversations: List<Conversation>?,
) {
    data class Fiche(val prose: String?, val notes: List<Note>)
    data class Note(val id: String, val date: String?, val author: String?, val text: String, val quote: String?, val page: JsonElement?, val raw: JsonObject) {
        val pageLabel: String? get() = (page as? JsonPrimitive)?.let { p ->
            if (p.isString) p.content else p.doubleOrNull?.toLong()?.toString()
        }
    }
    data class Highlight(val id: String, val text: String, val chapter: String?, val note: String?, val raw: JsonObject)
    data class Card(val body: String, val status: String, val url: String?) {
        val isDraft: Boolean get() = status != "published"
    }
    data class Conversation(val id: String, val title: String?, val date: String?, val messageCount: Int?, val summary: String?, val shareable: Boolean, val reason: String?)

    val title: String get() = entry.str("title").orEmpty()
    val kind: String? get() = entry.str("kind")
    val author: String? get() = entry["author"].strings().takeIf { it.isNotEmpty() }?.joinToString(", ")
    val sourceKind: String? get() = source?.str("kind")

    companion object {
        fun of(o: JsonObject): EntryExport {
            fun list(key: String) = (o[key] as? JsonArray)?.map { it as JsonObject }
            return EntryExport(
                entry = o["entry"] as JsonObject,
                source = o["source"] as? JsonObject,
                fiche = (o["fiche"] as? JsonObject)?.let { f ->
                    Fiche(f.str("prose"), (f["notes"] as JsonArray).map { n ->
                        n as JsonObject
                        Note(n.str("id")!!, n.str("date"), n.str("author"), n.str("text").orEmpty(), n.str("quote"), n["page"]?.takeIf { it !is JsonNull }, n)
                    })
                },
                highlights = list("highlights")?.map { h ->
                    val id = (h["id"] as JsonPrimitive).let { if (it.isString) it.content else it.intOrNull!!.toString() }
                    Highlight(id, h.str("text")!!, h.str("chapter"), h.str("note"), h)
                },
                card = (o["card"] as? JsonObject)?.let { Card(it.str("body")!!, it.str("status")!!, it.str("url")) },
                conversations = list("conversations")?.map { c ->
                    Conversation(c.str("id")!!, c.str("title"), c.str("date"), (c["message_count"] as? JsonPrimitive)?.intOrNull,
                        c.str("summary"), (c["shareable"] as JsonPrimitive).booleanOrNull!!, c.str("reason"))
                },
            )
        }
    }
}

/** `GET /api/conversations/:id/export` (spec B). */
data class ConversationExport(val title: String?, val date: String?, val messages: List<Message>, val raw: JsonObject) {
    data class Message(val role: String, val author: String?, val text: String) {
        val isMaurice: Boolean get() = role == "assistant"
    }
    companion object {
        fun of(o: JsonObject) = ConversationExport(
            o.str("title"), o.str("date"),
            (o["messages"] as JsonArray).map { m -> m as JsonObject; ConversationExport.Message(m.str("role")!!, m.str("author"), m.str("text")!!) },
            o,
        )
    }
}

/** `POST /api/v1/garden/entries/import` (spec C). Only what was ticked is in it. */
data class ImportPayload(
    val entry: JsonObject,
    val fromHousehold: String,
    val source: JsonObject? = null,
    val prose: String? = null,
    val notes: List<JsonObject> = emptyList(),
    val highlights: List<JsonObject> = emptyList(),
    val card: EntryExport.Card? = null,
    /** A conversation sent whole carries its messages; one sent as its summary carries only that (written as a dated note there). */
    val conversations: List<Pair<EntryExport.Conversation, ConversationExport?>> = emptyList(),
    /** Null: into the member's own notes there, shared with no one. */
    val shareWith: String? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        put("entry", entry)
        put("from_household", fromHousehold)
        source?.let { put("source", it) }
        prose?.let { put("prose", it) }
        if (notes.isNotEmpty()) put("notes", JsonArray(notes))
        if (highlights.isNotEmpty()) put("highlights", JsonArray(highlights))
        card?.let { c -> put("card", buildJsonObject { put("body", c.body); put("status", c.status); c.url?.let { put("url", it) } }) }
        if (conversations.isNotEmpty()) put("conversations", buildJsonArray {
            for ((meta, whole) in conversations) add(buildJsonObject {
                put("id", meta.id)
                put("mode", if (whole == null) "summary" else "whole")
                (whole?.title ?: meta.title)?.let { put("title", it) }
                (whole?.date ?: meta.date)?.let { put("date", it) }
                meta.summary?.let { put("summary", it) }
                whole?.raw?.get("messages")?.let { put("messages", it) }
            })
        })
        shareWith?.let { put("share_with", it) }
    }
}
