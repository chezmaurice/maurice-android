package eu.chezmaurice.parity

import eu.chezmaurice.carnet.entry.GiveMessages
import eu.chezmaurice.carnet.entry.GiveRecipient
import eu.chezmaurice.carnet.loadCarnetStrings
import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.ConversationExport
import eu.chezmaurice.core.carnet.CrossHouseholdApi
import eu.chezmaurice.core.carnet.EntryExport
import eu.chezmaurice.core.carnet.ImportPayload
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test

/** Between households: giving an entry, inviting a friend as a guest, an article read in its owner's notes — `CrossHouseholdParity.swift`. */
class CrossHouseholdParity {
    private val language = java.util.Locale.getDefault().language

    private fun recipient(c: ParityCase) = GiveRecipient(
        Household("h2", Parity.baseUrl, "Les Martin"), via = "u1",
        member = Member("u9", "Bea", "#6b6460", hasPin = false, role = Role.Guest, username = "bea"),
        isSelf = c.bool("self") ?: false,
    )

    /** A refusal as the sheet tells it apart. */
    private fun kind(e: Throwable): String = when (e) {
        is CrossHouseholdApi.Failure.Exists -> "exists:${e.collection}/${e.locale}/${e.slug}"
        CrossHouseholdApi.Failure.Unreachable -> "unreachable"
        CrossHouseholdApi.Failure.Offline -> "offline"
        CrossHouseholdApi.Failure.SessionEnded -> "sessionEnded"
        CrossHouseholdApi.Failure.Unsupported -> "unsupported"
        CrossHouseholdApi.Failure.TooLarge -> "tooLarge"
        is CrossHouseholdApi.Failure.Forbidden -> "forbidden:${e.code ?: "nil"}"
        is CrossHouseholdApi.Failure.Refused -> "refused:${e.code}"
        is CrossHouseholdApi.Failure.Other -> "other:${e.message}"
        else -> "error"
    }

    private fun export(e: EntryExport) = mapOf(
        "title" to e.title, "kind" to e.kind, "author" to e.author, "sourceKind" to e.sourceKind,
        "hasSource" to (e.source != null), "hasFiche" to (e.fiche != null), "prose" to e.fiche?.prose,
        "notes" to e.fiche?.notes.orEmpty().map { mapOf("id" to it.id, "date" to it.date, "author" to it.author, "text" to it.text, "quote" to it.quote, "pageLabel" to it.pageLabel) },
        "highlights" to e.highlights?.map { mapOf("id" to it.id, "text" to it.text, "chapter" to it.chapter, "note" to it.note) },
        "card" to e.card?.let { mapOf("body" to it.body, "status" to it.status, "url" to it.url, "isDraft" to it.isDraft) },
        "conversations" to e.conversations?.map {
            mapOf("id" to it.id, "title" to it.title, "date" to it.date, "messageCount" to it.messageCount, "summary" to it.summary, "shareable" to it.shareable, "reason" to it.reason)
        },
    )

    private fun decodeExport(c: ParityCase, key: String) = EntryExport.of(c.obj(key)!!)

    @Test fun requests() = Parity.check("carnet", "cross-household-requests") { c ->
        val session = ReadingFixture.session()
        val strings = loadCarnetStrings(language)
        val onScreen = CrossHouseholdApi(session.api()!!)
        val target = CrossHouseholdApi(ApiClient(Parity.baseUrl, "tok-u1"))
        val result: Any? = try {
            when (c.string("op")) {
                "guestInvite" -> onScreen.guestInvite().let { mapOf("code" to it.code, "link" to it.link, "expiresAt" to it.expiresAt) }
                "guestInviteState" -> onScreen.guestInviteState(c.string("code")!!)
                "roster" -> target.roster().map { mapOf("id" to it.id, "displayName" to it.displayName, "username" to it.username, "role" to it.role?.name?.lowercase(), "avatarUrl" to it.avatarUrl) }
                "export" -> export(target.export(c.string("collection")!!, c.string("locale")!!, c.string("slug")!!))
                "conversation" -> target.conversation(c.string("id")!!).let { x ->
                    mapOf("title" to x.title, "date" to x.date, "messages" to x.messages.map { mapOf("role" to it.role, "author" to it.author, "text" to it.text, "isMaurice" to it.isMaurice) })
                }
                "import" -> {
                    val e = decodeExport(c, "export")
                    val pick = c.obj("pick") ?: JsonObject(emptyMap())
                    fun on(key: String) = (pick[key] as? JsonPrimitive)?.booleanOrNull == true
                    fun ids(key: String) = (pick[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
                    val payload = ImportPayload(
                        entry = e.entry, fromHousehold = c.string("from")!!,
                        source = if (on("source")) e.source else null,
                        prose = if (on("prose")) e.fiche?.prose else null,
                        notes = e.fiche?.notes.orEmpty().filter { it.id in ids("notes") }.map { it.raw },
                        highlights = e.highlights.orEmpty().filter { it.id in ids("highlights") }.map { it.raw },
                        card = if (on("card")) e.card else null,
                        conversations = (pick["conversations"] as? JsonArray).orEmpty().mapNotNull { item ->
                            item as JsonObject
                            val meta = e.conversations?.firstOrNull { it.id == (item["id"] as? JsonPrimitive)?.content } ?: return@mapNotNull null
                            meta to (item["whole"] as? JsonObject)?.let { runCatching { ConversationExport.of(it) }.getOrNull() }
                        },
                        shareWith = c.string("shareWith"),
                    )
                    mapOf("ref" to target.import(payload).ref)
                }
                else -> mapOf("unknown op" to c.string("op"))
            }
        } catch (e: Throwable) {
            if (e is AssertionError) throw e
            mapOf("failure" to kind(e), "message" to GiveMessages.describe(e, recipient(c), strings))
        }
        mapOf("requests" to Parity.requests(), "result" to Parity.toJson(result))
    }

    @Test fun words() = Parity.check("carnet", "cross-household-words") { c ->
        val strings = loadCarnetStrings(language)
        when (c.string("op")) {
            "sources" -> c.array("kinds")!!.map { GiveMessages.sourceLabel((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content, strings) }
            "lines" -> decodeExport(c, "export").let { e ->
                mapOf(
                    "notes" to e.fiche?.notes.orEmpty().map { mapOf("line" to GiveMessages.noteLine(it, strings, language), "heading" to GiveMessages.noteHeading(it, language)) },
                    "conversations" to e.conversations.orEmpty().map { GiveMessages.conversationLine(it, language) },
                )
            }
            "dates" -> c.array("dates")!!.map { GiveMessages.dateLabel((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content, language) }
            "plain" -> GiveMessages.plainText(c.string("html").orEmpty())
            else -> mapOf("unknown op" to c.string("op"))
        }
    }

    @Test fun article() = Parity.check("carnet", "cross-household-article") { c ->
        when (c.string("op")) {
            "shared" -> Article.shared(ShelfFixture.entry(c)).let { a ->
                ReadingObs.article(a) + mapOf("owner" to a.owner, "author" to a.author, "summary" to a.summary)
            }
            "text" -> {
                val calibre = CalibreApi(ReadingFixture.api())
                ReadingObs.exchange { mapOf("text" to calibre.articleText(c.string("slug")!!, c.string("locale")!!, c.string("owner")).text) }
            }
            else -> mapOf("unknown op" to c.string("op"))
        }
    }
}
