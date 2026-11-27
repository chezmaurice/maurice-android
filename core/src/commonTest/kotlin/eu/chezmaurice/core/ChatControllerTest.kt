package eu.chezmaurice.core

import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mock engine answers on real threads, so the controller runs on
 * Dispatchers.Default and the tests wait for its state, not for virtual time.
 */
class ChatControllerTest {
    // One task at a time, as the app's main thread runs the controller; a
    // multi-threaded pool made races the app cannot have.
    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private suspend fun ChatController.settle() = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            combine(state, live) { s, l -> s to l }.first { (s, l) ->
                !s.turnRunning && l == null && !s.loadingMessages && s.messages.none { it.id.startsWith("local-") }
            }
        }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val ndjson = headersOf(HttpHeaders.ContentType, "application/x-ndjson")

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(MauriceJson) }
            install(HttpTimeout)
        }

    @Test fun aTurnStreamsThenLandsInTheThread() = runTest {
        var turnPolls = 0
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/conversations" ->
                    respond("""{"id":"c1","created_at":"2026-10-21 08:00:00","updated_at":"2026-10-21 08:00:00"}""", HttpStatusCode.Created, json)
                request.method == HttpMethod.Post && path == "/api/conversations/c1/messages" -> respond(
                    listOf(
                        """{"type":"thinking"}""",
                        """{"type":"tool_call","tool":"corpus__search","status":"start"}""",
                        """{"type":"tool_call","tool":"corpus__search","status":"end"}""",
                        """{"type":"text_delta","text":"Bon"}""",
                        """{"type":"text_delta","text":"jour."}""",
                        """{"type":"done","message_id":"m2"}""",
                    ).joinToString("\n"),
                    HttpStatusCode.OK, ndjson,
                )
                path == "/api/conversations/c1" -> respond(
                    """{"id":"c1","messages":[{"id":"m1","role":"user","content":"Salut","created_at":"x"},{"id":"m2","role":"assistant","content":"Bonjour.","created_at":"x"}],"participants":[]}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/conversations/c1/turn" -> { turnPolls++; respond("", HttpStatusCode.NoContent) }
                path == "/api/conversations" -> respond("""[{"id":"c1","title":"Salut","updated_at":"x","created_at":"x"}]""", HttpStatusCode.OK, json)
                else -> respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        controller.send("Salut")
        controller.settle()
        val state = controller.state.value
        assertNull(controller.live.value)
        assertNull(state.error)
        assertEquals(listOf("m1", "m2"), state.messages.map { it.id })
        // The list is refreshed last, for the server's title.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { controller.state.first { it.conversations.singleOrNull()?.title == "Salut" } }
        }
        assertEquals(0, turnPolls)
    }

    @Test fun aDroppedStreamReattaches() = runTest {
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/conversations/c1/messages" ->
                    respond("""{"type":"text_delta","text":"Bon"}""", HttpStatusCode.OK, ndjson)
                path == "/api/conversations/c1/turn" -> respond(
                    """{"type":"resume","text":"Bonjour","data":[],"tool":null,"usage":null,"finished":true}""" + "\n" +
                        """{"type":"done","message_id":"m2"}""",
                    HttpStatusCode.OK, ndjson,
                )
                path == "/api/conversations/c1" -> respond("""{"id":"c1","messages":[],"participants":[]}""", HttpStatusCode.OK, json)
                path == "/api/conversations" -> respond("[]", HttpStatusCode.OK, json)
                else -> respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        // Pretend c1 is open, then send.
        controller.open("c1")
        controller.settle()
        controller.send("Salut")
        controller.settle()
        assertNull(controller.live.value)
        assertNull(controller.state.value.error)
    }

    @Test fun busyServerSaysSo() = runTest {
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/conversations/c1/messages" ->
                    respond("""{"error":"A reply is already in progress"}""", HttpStatusCode.Conflict, json)
                path == "/api/conversations/c1/turn" -> respond("", HttpStatusCode.NoContent)
                path == "/api/conversations/c1" -> respond("""{"id":"c1","messages":[],"participants":[]}""", HttpStatusCode.OK, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        controller.open("c1")
        controller.settle()
        controller.send("Salut")
        controller.settle()
        assertEquals("chat.error.busy", controller.state.value.error)
        assertEquals(emptyList(), controller.state.value.messages)
    }

    /** An empty reply ends with an error: banner, no reconnect, nothing stored. */
    @Test fun anEmptyReplyEndsWithItsError() = runTest {
        var turnPolls = 0
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/conversations/c1/messages" -> {
                    respond(
                        """{"type":"thinking"}""" + "\n" + """{"type":"error","message":"Maurice est revenu sans réponse."}""",
                        HttpStatusCode.OK, ndjson,
                    )
                }
                path == "/api/conversations/c1/turn" -> { turnPolls++; respond("", HttpStatusCode.NoContent) }
                path == "/api/conversations/c1" -> respond("""{"id":"c1","messages":[{"id":"m1","role":"user","content":"Salut","created_at":"x"}],"participants":[]}""", HttpStatusCode.OK, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        controller.open("c1")
        controller.settle()
        // Opening re-attaches once on its own; let that happen first.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (turnPolls < 1) kotlinx.coroutines.delay(10) } }
        val before = turnPolls
        controller.send("Salut")
        controller.settle()
        assertEquals("Maurice est revenu sans réponse.", controller.state.value.error)
        assertEquals(listOf("m1"), controller.state.value.messages.map { it.id })
        assertEquals(before, turnPolls, "no re-attach after the error")
    }

    /** After our own ⏹, "Stopped before any answer." is not shown as an error. */
    @Test fun ourOwnStopIsNotAnError() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/conversations/c1/messages" -> {
                    gate.await()
                    respond("""{"type":"error","message":"Arrêté avant toute réponse."}""", HttpStatusCode.OK, ndjson)
                }
                path == "/api/conversations/c1/turn/stop" -> { gate.complete(Unit); respond("""{"ok":true}""", HttpStatusCode.OK, json) }
                path == "/api/conversations/c1/turn" -> respond("", HttpStatusCode.NoContent)
                path == "/api/conversations/c1" -> respond("""{"id":"c1","messages":[],"participants":[]}""", HttpStatusCode.OK, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        controller.open("c1")
        controller.settle()
        controller.send("Salut")
        controller.stop()
        controller.settle()
        assertNull(controller.state.value.error)
    }

    /** An older server: a finished resume with nothing after it ends the turn. */
    @Test fun aFinishedResumeWithoutEndIsTheEnd() = runTest {
        var turnPolls = 0
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/conversations/c1/messages" ->
                    respond("""{"type":"thinking"}""", HttpStatusCode.OK, ndjson)
                path == "/api/conversations/c1/turn" -> {
                    turnPolls++
                    respond("""{"type":"resume","text":"","data":[],"tool":null,"usage":null,"finished":true}""", HttpStatusCode.OK, ndjson)
                }
                path == "/api/conversations/c1" -> respond("""{"id":"c1","messages":[],"participants":[]}""", HttpStatusCode.OK, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        controller.open("c1")
        controller.settle()
        turnPolls = 0
        controller.send("Salut")
        controller.settle()
        assertNull(controller.state.value.error)
        assertEquals(1, turnPolls, "one re-attach, then the turn is over")
    }

    /** The × on the held entry's tile (maurice 13597ba): DELETE …/entry, then held from nothing. */
    /** A household older than the app has no such route: said in plain words, the entry stays (22 November 2026). */
    @Test fun anOlderHouseholdSaysSoAndKeepsTheEntry() = runTest {
        for ((status, body) in listOf(
            HttpStatusCode.Forbidden to "<html>Forbidden</html>",
            HttpStatusCode.NotFound to """{"error":"Not found","code":"unknown_route"}""",
        )) {
            val http = client { request ->
                when {
                    request.method == HttpMethod.Delete -> respond(body, status, json)
                    request.url.encodedPath == "/api/conversations/c1" -> respond(
                        """{"id":"c1","messages":[],"participants":[],"entry":{"ref":"books/fr/le-gene","collection":"books","title":"Le gène"}}""",
                        HttpStatusCode.OK, json,
                    )
                    else -> respond("[]", HttpStatusCode.OK, json)
                }
            }
            val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
            controller.open("c1")
            controller.settle()
            assertEquals(false, controller.releaseEntry())
            assertEquals("books/fr/le-gene", controller.state.value.activeEntry?.ref, "the tile stays ($status)")
            assertEquals(eu.chezmaurice.core.chat.ChatErrors.HOUSEHOLD_OLDER, controller.state.value.error, "plain words ($status)")
        }
    }

    @Test fun anUnknownRouteIsSaidAsNotYetThere() {
        val e = eu.chezmaurice.core.net.ApiError(404, "Not found", fromServer = true, code = "unknown_route")
        kotlin.test.assertTrue(e.isUnknownRoute)
        kotlin.test.assertFalse(eu.chezmaurice.core.net.ApiError(404, "Not found", fromServer = true).isUnknownRoute)
    }

    @Test fun lettingGoOfTheEntry() = runTest {
        var released = 0
        var started = false
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Delete && path == "/api/conversations/c1/entry" ->
                    if (started) respond("""{"error":"started"}""", HttpStatusCode.Conflict, json)
                    else { released++; respond("""{"ok":true}""", HttpStatusCode.OK, json) }
                path == "/api/conversations/c1" -> respond(
                    """{"id":"c1","messages":[],"participants":[],"entry":{"ref":"books/fr/le-gene","collection":"books","title":"Le gène"}}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val controller = ChatController(ChatApi(ApiClient("https://maison.test", "t", http)), scope, "u1", revealTimeoutMillis = 0)
        controller.open("c1")
        controller.settle()
        assertEquals("books/fr/le-gene", controller.state.value.activeEntry?.ref)
        assertEquals(true, controller.releaseEntry())
        assertEquals(1, released)
        assertNull(controller.state.value.activeEntry)
        // Nothing held any more: nothing to send.
        assertEquals(false, controller.releaseEntry())
        assertEquals(1, released)

        // After the first message the server refuses, and the entry stays.
        controller.open("c1")
        controller.settle()
        started = true
        assertEquals(false, controller.releaseEntry())
        assertEquals("books/fr/le-gene", controller.state.value.activeEntry?.ref)
        kotlin.test.assertNotNull(controller.state.value.error)
    }
}

class RevealTest {
    /** The message waits for the row to finish typing, then takes its place. */
    @Test fun theMessageWaitsForTheReveal() = kotlinx.coroutines.test.runTest {
        val json = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
        val ndjson = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/x-ndjson")
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == io.ktor.http.HttpMethod.Post && path == "/api/conversations/c1/messages" -> respond(
                    """{"type":"text_delta","text":"Bonjour."}""" + "\n" + """{"type":"done","message_id":"m2"}""",
                    io.ktor.http.HttpStatusCode.OK, ndjson,
                )
                path == "/api/conversations/c1" -> respond("""{"id":"c1","messages":[],"participants":[]}""", io.ktor.http.HttpStatusCode.OK, json)
                path == "/api/conversations/c1/turn" -> respond("", io.ktor.http.HttpStatusCode.NoContent)
                else -> respond("[]", io.ktor.http.HttpStatusCode.OK, json)
            }
        }) { expectSuccess = false; install(io.ktor.client.plugins.HttpTimeout) }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))
        val controller = eu.chezmaurice.core.chat.ChatController(
            eu.chezmaurice.core.chat.ChatApi(eu.chezmaurice.core.net.ApiClient("https://maison.test", "t", http)), scope, "u1",
        )
        controller.open("c1")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { controller.state.first { !it.loadingMessages } }
            controller.send("Salut")
            val finished = kotlinx.coroutines.withTimeout(5_000) { controller.live.first { it?.finishedId == "m2" } }!!
            kotlin.test.assertEquals("Bonjour.", finished.text, "live was $finished")
            kotlin.test.assertFalse(controller.state.value.turnRunning, "the composer is free while the row types")
            kotlin.test.assertTrue(controller.state.value.messages.none { it.id == "m2" }, "not before the reveal")
            controller.revealed("m2")
            kotlinx.coroutines.withTimeout(5_000) { controller.live.first { it == null } }
        }
    }

    @Test fun backFromTheBackgroundTheListIsReadAgain() = runTest {
        // While the app slept, someone added the member to a room: no socket
        // carried it, only a notification. Coming back must show it.
        var rooms = """[{"id":"c1","title":"Salut","updated_at":"x","created_at":"x"}]"""
        val json = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/conversations" -> respond(rooms, io.ktor.http.HttpStatusCode.OK, json)
                else -> respond("""{"error":"Not found"}""", io.ktor.http.HttpStatusCode.NotFound, json)
            }
        }) { expectSuccess = false }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))
        val api = eu.chezmaurice.core.net.ApiClient("https://maison.test", "t", http)
        val controller = eu.chezmaurice.core.chat.ChatController(eu.chezmaurice.core.chat.ChatApi(api), scope, "u1", client = api)
        controller.loadConversations()
        controller.resumeLive()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(5_000) { controller.state.first { it.conversations.size == 1 } } }
        controller.pauseLive()
        rooms = """[{"id":"c2","title":"Le salon de Nadia","updated_at":"y","created_at":"y"},{"id":"c1","title":"Salut","updated_at":"x","created_at":"x"}]"""
        controller.resumeLive()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(5_000) { controller.state.first { it.conversations.size == 2 } } }
        kotlin.test.assertEquals("c2", controller.state.value.conversations.first().id)
        controller.pauseLive()
    }
}
