package eu.chezmaurice.core.domains

import eu.chezmaurice.core.model.BriefPage
import eu.chezmaurice.core.model.BriefRefresh
import eu.chezmaurice.core.model.Brief
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainConversations
import eu.chezmaurice.core.model.DomainsOverview
import eu.chezmaurice.core.model.ModelInfo
import eu.chezmaurice.core.model.ProposalCounts
import eu.chezmaurice.core.model.ProposalDecision
import eu.chezmaurice.core.model.ProposalDetail
import eu.chezmaurice.core.model.ProposalOutcome
import eu.chezmaurice.core.model.ProposalsList
import eu.chezmaurice.core.model.ToolFamily
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import io.ktor.http.HttpMethod
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import eu.chezmaurice.core.model.SeededNotes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable private data class ModelId(val id: String? = null)
@Serializable private data class BriefEnvelope(val brief: Brief? = null)
@Serializable private data class Refreshed(val outcome: String = "", val sources: Int = 0, val error: String? = null)
@Serializable private data class ProposalEnvelope(val proposal: ProposalDetail)

/**
 * The domain routes: `/api/maurices` (the rows), `/api/domains` (briefs,
 * bound conversations, the night's proposals), `/api/models`,
 * `/api/tool-families` — what `MauriceStore.swift` and the proposal half of
 * `ChatService.swift` call.
 */
class DomainsApi(private val api: ApiClient) {

    suspend fun rows(): List<Domain> = list("/api/maurices", Domain.serializer())

    suspend fun overview(): DomainsOverview = api.get("/api/domains")

    suspend fun models(): List<ModelInfo> = list("/api/models", ModelInfo.serializer())

    suspend fun families(): List<ToolFamily> = list("/api/tool-families", ToolFamily.serializer())

    /** A list of rows, each read on its own: one that cannot be read is left out (iOS's `compactMap`). */
    private suspend fun <T> list(path: String, row: KSerializer<T>): List<T> =
        MauriceJson.decodeEach(row, MauriceJson.parseToJsonElement(api.send(HttpMethod.Get, path).bodyAsText()))

    suspend fun everydayModel(): String? = api.get<ModelId>("/api/models/everyday").id

    /** The everyday Maurice's model, the member's own; answers what the server kept. */
    suspend fun setEverydayModel(id: String?): String? =
        api.decode<ModelId>(api.send(HttpMethod.Put, "/api/models/everyday", JsonObject(mapOf("id" to (id?.let(::JsonPrimitive) ?: JsonNull))))).id

    /** Any subset of a row's fields: `{"model": id}` from the model pill, `{"kind": …}` to re-sort. */
    suspend fun patch(id: String, fields: JsonObject): Domain =
        api.decode(api.send(HttpMethod.Patch, "/api/maurices/$id", fields))

    /**
     * Create ([id] null) or edit a row. The server keeps one domain per name
     * for a member (case and accents aside): a 409 names the one that has it.
     */
    suspend fun save(id: String?, fields: JsonObject): SaveOutcome {
        val response = api.http.request(api.url(if (id == null) "/api/maurices" else "/api/maurices/$id")) {
            method = if (id == null) HttpMethod.Post else HttpMethod.Patch
            api.token?.let { bearerAuth(it) }
            setBody(TextContent(MauriceJson.encodeToString(JsonObject.serializer(), fields), ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        return when {
            response.status.isSuccess() -> SaveOutcome.Saved(MauriceJson.decodeFromString(Domain.serializer(), text))
            // A 409 that does not say which domain has the name is a plain failure (as on iOS).
            response.status.value == 409 && existing(text) != null -> {
                val name = (existing(text)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                SaveOutcome.NameTaken(name ?: (fields["name"] as? JsonPrimitive)?.content.orEmpty())
            }
            else -> SaveOutcome.Failed
        }
    }

    private fun existing(text: String): JsonObject? =
        runCatching { MauriceJson.parseToJsonElement(text).jsonObject["existing"] as? JsonObject }.getOrNull()

    suspend fun delete(id: String) {
        api.send(HttpMethod.Delete, "/api/maurices/$id")
    }

    // ── Briefs ─────────────────────────────────────────────

    /**
     * Null when the domain is not the member's (404). A brief that cannot be
     * read is none, the page still found; seeded notes count only when there
     * are some (`MauriceStore.loadBrief`).
     */
    suspend fun brief(id: String): BriefPage? {
        val page = try {
            api.get<JsonObject>("/api/domains/$id/brief")
        } catch (e: ApiError) {
            if (e.status == 404) return null else throw e
        }
        fun <T> part(key: String, s: KSerializer<T>): T? =
            page[key]?.let { runCatching { MauriceJson.decodeFromJsonElement(s, it) }.getOrNull() }
        return BriefPage(brief = part("brief", Brief.serializer()), notes = part("notes", SeededNotes.serializer())?.takeIf { it.total > 0 })
    }

    /** The member's words; empty erases. Answers the brief as kept. */
    suspend fun saveBrief(id: String, text: String): Brief? =
        api.decode<BriefEnvelope>(api.send(HttpMethod.Put, "/api/domains/$id/brief", buildJsonObject { put("text", text) })).brief

    /** Erased when the server said 200, whatever it answered besides (`MauriceStore.deleteBrief`). */
    suspend fun deleteBrief(id: String): Boolean =
        api.send(HttpMethod.Delete, "/api/domains/$id/brief").status.value == 200

    /**
     * One model call, seconds. 429: the night's allowance is spent; 502: the
     * call failed, for the reason the server gives (or none); any other answer
     * fails with the server's error, else its HTTP status (`refreshBrief`).
     */
    suspend fun refreshBrief(id: String): BriefRefresh {
        val (status, r) = try {
            val response = api.send(HttpMethod.Post, "/api/domains/$id/brief/refresh", JsonObject(emptyMap()), timeoutMillis = 180_000)
            response.status.value to (runCatching { MauriceJson.decodeFromString(Refreshed.serializer(), response.bodyAsText()) }.getOrNull() ?: Refreshed())
        } catch (e: ApiError) {
            e.status to Refreshed(error = e.message.takeIf { e.fromServer })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            0 to Refreshed()   // never reached the server
        }
        val error = r.error.orEmpty()
        return when {
            status == 200 && r.outcome == "written" -> BriefRefresh.Written(r.sources)
            status == 200 && r.outcome == "unchanged" -> BriefRefresh.Unchanged
            status == 429 -> BriefRefresh.Capped
            status == 502 -> BriefRefresh.Failed(error)
            else -> BriefRefresh.Failed(error.ifEmpty { "HTTP $status" })
        }
    }

    suspend fun conversations(id: String, autoOnly: Boolean = false): DomainConversations =
        api.get("/api/domains/$id/conversations?limit=100${if (autoOnly) "&auto=1" else ""}")

    // ── Proposals ──────────────────────────────────────────

    suspend fun proposals(): ProposalsList = api.get("/api/domains/proposals")

    suspend fun markProposalsSeen(): ProposalCounts = api.post("/api/domains/proposals/seen")

    suspend fun proposal(id: String): ProposalDetail = api.get<ProposalEnvelope>("/api/domains/proposals/$id").proposal

    suspend fun apply(decisions: List<ProposalDecision>): ProposalOutcome = api.post(
        "/api/domains/proposals/apply",
        buildJsonObject {
            putJsonArray("items") {
                for (d in decisions) add(buildJsonObject {
                    put("id", d.id); put("action", d.action); put("seed", d.seed)
                    d.name?.let { put("name", it) }
                    d.summary?.let { put("summary", it) }
                })
            }
        },
    )

    suspend fun restore(id: String) {
        api.send(HttpMethod.Post, "/api/domains/proposals/$id/restore", buildJsonObject {})
    }

    suspend fun merge(ids: List<String>, name: String?) {
        api.send(HttpMethod.Post, "/api/domains/proposals/merge", buildJsonObject {
            put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            name?.let { put("name", it) }
        })
    }

    /** Cut a part out of a proposal: it becomes a proposal of its own. */
    suspend fun split(id: String, name: String, conversationIds: List<String>, mail: List<String>) {
        api.send(HttpMethod.Post, "/api/domains/proposals/$id/split", buildJsonObject {
            putJsonArray("parts") {
                add(buildJsonObject {
                    put("name", name)
                    put("conversation_ids", buildJsonArray { conversationIds.forEach { add(JsonPrimitive(it)) } })
                    put("mail", buildJsonArray { mail.forEach { add(JsonPrimitive(it)) } })
                })
            }
        })
    }

}

/** What saving a domain came to. */
sealed interface SaveOutcome {
    data class Saved(val row: Domain) : SaveOutcome
    /** Another domain of the member's has that name already. */
    data class NameTaken(val name: String) : SaveOutcome
    data object Failed : SaveOutcome
}
