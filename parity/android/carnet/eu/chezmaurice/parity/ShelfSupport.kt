package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.EntryConversation
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.core.carnet.EntryPage
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.MemberBrief
import eu.chezmaurice.core.carnet.MineItem
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.ShelfSource
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Carnet's fixture for the parity suite (lot "Carnet shelf & entries"): a
 * blank device paired with the fake household, one member signed in, as
 * Carnet builds its [Session] (prefix "carnet") — the Android side of
 * `ShelfSupport.swift`.
 */
object ShelfFixture {
    fun session(member: String = "u1", token: String = "tok-u1", displayName: String = "Alice"): Session =
        MauriceFixture.session(prefix = "carnet", member = member, token = token, displayName = displayName)

    fun garden(session: Session = session()): GardenApi = GardenApi(session.api()!!)

    /** A row as the list hands it to an entry's page, from the case's JSON. */
    fun entry(c: ParityCase, key: String = "entry"): ShelfEntry =
        MauriceJson.decodeFromString(ShelfEntry.serializer(), c.text(key))

    /** What the member is told when a call fails: the server's `error`, else the raw body. */
    fun failure(e: Throwable): String = when (e) {
        is ApiError -> "server: " + e.message
        is SerializationException, is IllegalArgumentException, is ClassCastException -> "undecodable"
        is java.io.IOException -> "network"
        else -> "other: $e"
    }

    suspend fun exchange(body: suspend () -> Any?): Map<String, Any?> {
        val result: Any? = try { Parity.toJson(body()) } catch (e: Throwable) {
            if (e is AssertionError) throw e
            mapOf("failed" to failure(e))
        }
        return mapOf("requests" to Parity.requests(), "result" to result)
    }
}

object ShelfObserve {
    fun member(m: MemberBrief) = mapOf("id" to m.id, "username" to m.username, "displayName" to m.displayName)

    fun entry(e: ShelfEntry): Map<String, Any?> = mapOf(
        "id" to e.id,
        "kind" to e.kind.wire,
        "kindLabel" to e.kind.label,
        "collection" to e.collection,
        "locale" to e.locale,
        "slug" to e.slug,
        "title" to e.title,
        "byline" to e.byline,
        "kindLine" to e.kindLine,
        "coverLetter" to e.coverLetter,
        "squareCover" to e.kind.squareCover,
        "date" to e.date,
        "updatedAt" to e.updatedAt,
        "image" to e.image,
        "tags" to e.tags,
        "rating" to e.rating,
        "status" to e.status,
        "isLibraryOnly" to e.isLibraryOnly,
        "isUntouched" to e.isUntouched,
        "noteCount" to e.noteCount,
        "opened" to e.mine?.opened,
        "isForeign" to e.isForeign,
        "isShared" to e.isShared,
        "people" to e.people.map(::member),
        "owner" to e.owner?.let(::member),
        "archived" to e.archived,
        "shared" to e.shared?.state,
        "translations" to e.translations.map { mapOf("id" to it.id, "locale" to it.locale, "shared" to it.shared?.state) },
        "source" to e.source?.let(::source),
    )

    fun source(s: ShelfSource): Map<String, Any?> = mapOf(
        "type" to s.type,
        "isBook" to s.isBook,
        "isReadable" to s.isReadable,
        "bookId" to s.bookId,
        "chapters" to s.chapters,
        "url" to s.url,
        "captured" to s.captured,
        "wordCount" to s.wordCount,
        "readingMinutes" to s.readingMinutes,
        "progress" to s.progress?.let {
            mapOf(
                "chapterSlug" to it.chapterSlug, "chapterTitle" to it.chapterTitle, "fraction" to it.fraction,
                "finished" to it.finished, "tracking" to it.tracking, "updatedAt" to it.updatedAt,
            )
        },
        "summary" to s.summary?.let { mapOf("text" to it.text, "long" to it.long, "current" to it.current) },
        "sharedByUsername" to s.sharedBy?.username,
    )

    fun item(i: MineItem): Map<String, Any?> = mapOf(
        "id" to i.id,
        "kind" to i.kind,
        "date" to i.date,
        "text" to i.text,
        "quote" to i.quote,
        "summary" to i.summary,
        "page" to i.place?.page,
        "chapterTitle" to i.place?.chapterTitle,
        "view" to i.place?.view,
        "from" to i.from?.let { mapOf("label" to it.label, "entryId" to it.entryId, "publishedUrl" to it.publishedUrl) },
        "author" to i.author?.let(::member),
        "conversation" to i.conversation?.let { mapOf("id" to it.id, "initiator" to it.initiator?.id) },
    )

    fun detail(d: EntryDetail): Map<String, Any?> = mapOf(
        "mine" to d.mine?.let { m ->
            mapOf(
                "notes" to m.notes, "opened" to m.opened, "webPath" to m.webPath, "file" to m.file, "prose" to m.prose,
                "items" to m.items.map(::item),
            )
        },
        "shared" to d.shared?.let { s ->
            mapOf(
                "state" to s.state, "webPath" to s.webPath, "file" to s.file, "publicUrl" to s.publicUrl,
                "title" to s.title, "body" to s.body,
                "links" to s.links.map { mapOf("basename" to it.basename, "label" to it.label, "entryId" to it.entryId, "publishedUrl" to it.publishedUrl) },
            )
        },
    )

    fun written(w: EntryPage): Map<String, Any?> = mapOf(
        "entry" to entry(w.entry),
        "detail" to detail(w.detail),
        "deploy" to w.deploy?.let { mapOf("status" to it.status, "error" to it.error, "inProgress" to it.inProgress, "failed" to it.failed) },
    )

    fun conversation(c: EntryConversation, `when`: String): Map<String, Any?> = mapOf(
        "id" to c.id, "displayTitle" to c.displayTitle, "when" to `when`, "messageCount" to c.messageCount, "via" to c.via,
        "maurice" to c.maurice?.name,
        "summary" to c.summary?.let { mapOf("id" to it.id, "text" to it.text, "writtenAt" to it.writtenAt, "stale" to it.stale) },
    )

    suspend fun requests(prefix: String = "/api/"): List<Any?> =
        (Parity.requests() as JsonArray).filter { it.jsonObject["path"]?.jsonPrimitive?.content?.startsWith(prefix) == true }
}
