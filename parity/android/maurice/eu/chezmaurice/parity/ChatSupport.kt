package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.chat.Streaming
import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.parity.Parity.str
import eu.chezmaurice.ui.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Helpers of the Chat lot (ChatTurn, ChatThread, ChatCards, ChatArticle).
 * They read fields and rename them, as ChatSupport.swift does.
 */
object ChatSupport {
    /** The English words, as the app loads them (`loadMauriceStrings`): the iOS table, the Android one over it. */
    val strings: Strings by lazy {
        val dir = File(Parity.root.parentFile, "maurice/shared/src/commonMain/composeResources/files")
        val table = Strings.parse(File(dir, "strings/en.strings").readText()) +
            (File(dir, "android/en.strings").takeIf { it.exists() }?.let { Strings.parse(it.readText()) } ?: emptyMap())
        Strings(table, table)
    }

    fun blocks(raw: JsonArray?): List<DataBlock> = raw.orEmpty().mapNotNull {
        runCatching { MauriceJson.decodeFromJsonElement(DataBlock.serializer(), it) }.getOrNull()
    }

    @OptIn(ExperimentalTime::class)
    fun iso(i: Instant?): String? = i?.let {
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()))
    }

    // ── The household's log

    suspend fun log(): List<JsonObject> = (Parity.requests() as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun sent(log: List<JsonObject>) = log.filter { it["method"].str() != "GET" }
        .map { mapOf("method" to it["method"], "path" to it["path"], "body" to it["body"]) }

    fun listReads(log: List<JsonObject>) = log.filter {
        it["method"].str() == "GET" && (it["path"].str() == "/api/conversations/search" ||
            (it["path"].str() == "/api/conversations" && (it["query"] as? JsonObject)?.get("before_id") != null))
    }.map { mapOf("path" to it["path"], "query" to it["query"]) }

    fun attached(log: List<JsonObject>) = log.any { it["method"].str() == "GET" && it["path"].str().orEmpty().endsWith("/turn") }

    // ── The controller

    fun controller(scope: CoroutineScope): ChatController =
        ChatController(ChatApi(MauriceFixture.session().api()!!), scope, "u1", revealTimeoutMillis = 5_000)

    /** The banner as ChatScreen draws it: a key of the app's words, or the server's own. */
    fun banner(chat: ChatController): String? = chat.state.value.error?.let { if (strings.has(it)) strings(it) else it }

    /**
     * Wait until the controller is idle — no turn, no load — and the household
     * has heard nothing new for a while (a refresh after a turn, a debounce).
     */
    suspend fun settle(chat: ChatController, quietMillis: Long = 700) = withContext(Dispatchers.Default) {
        withTimeoutOrNull(40_000) {
            var last = -1
            var since = System.currentTimeMillis()
            while (true) {
                val s = chat.state.value
                val busy = s.turnRunning || chat.live.value != null || s.loadingList || s.loadingMessages || s.searching
                val n = log().size
                if (busy || n != last) { last = n; since = System.currentTimeMillis() }
                else if (System.currentTimeMillis() - since >= quietMillis) break
                delay(100)
            }
        }
    }

    /**
     * The reply row: once the turn is stored it types the rest out, then says
     * so (`revealed`) — what LiveReply does. The last such reply, if it had words.
     */
    class Replies(chat: ChatController, scope: CoroutineScope) {
        var last: Streaming? = null
        val job: Job = scope.launch {
            chat.live.collect { l ->
                val id = l?.finishedId ?: return@collect
                last = l
                chat.revealed(id)
            }
        }
        fun observed(): Any? = last?.takeIf { it.text.isNotEmpty() }?.let {
            mapOf("text" to it.text, "tools" to it.data.map { b -> b.tool }, "cost" to it.usage?.cost, "model" to it.usage?.model)
        }
    }

    fun thread(chat: ChatController, ids: Boolean) = chat.state.value.messages.map { m ->
        buildMap {
            put("role", m.role); put("content", m.content); put("author", m.authorId)
            put("tools", m.data.orEmpty().map { it.tool }); put("cost", m.usage?.cost)
            if (ids) put("id", m.id)
        }
    }

    fun list(chat: ChatController) = chat.state.value.conversations.map { mapOf("id" to it.id, "title" to it.title, "maurice" to it.mauriceId) }

    fun participants(chat: ChatController) = chat.state.value.participants.map {
        mapOf("id" to it.memberId, "name" to it.displayName, "role" to it.role, "color" to it.avatarColor)
    }

    fun trails(chat: ChatController) = chat.state.value.trails.mapValues { (_, steps) ->
        steps.map { mapOf("tool" to it.tool, "count" to it.count, "running" to it.running) }
    }

    /** Input `after`: the household's answers to the action. */
    fun after(input: JsonElement?): JsonElement = input ?: JsonArray(emptyList())
}
