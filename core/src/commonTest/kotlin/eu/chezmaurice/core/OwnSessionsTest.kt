package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.settings.SettingsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "My devices" (`DevicesView`, 26 November 2026): the member's sessions, one signed out, all the others. */
class OwnSessionsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val sent = mutableListOf<String>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String) = SettingsApi(ApiClient("https://maison.test", "tok", HttpClient(MockEngine { request ->
        sent += "${request.method.value} ${request.url.encodedPath}"
        respond(body, status, json)
    })))

    @Test fun theDevicesAreReadAndNamed() = runTest {
        val list = api(body = """{"sessions":[
            {"id":"a1","created_at":"2026-11-20 09:00:00","last_used_at":"2026-11-26 20:00:00","current":true,
             "device":{"platform":"android","model":"Pixel 8","app":"maurice","app_version":"0.5.20"}},
            {"id":"b2","created_at":"2026-11-02 10:00:00","last_used_at":null,"current":false,
             "device":{"platform":"iOS","model":"iPhone17,1","app":"carnet","app_version":"1.4 (212)"}},
            {"id":"c3","created_at":"2026-10-01 10:00:00","current":false,"device":null}
        ]}""").ownSessions()
        assertEquals("GET /api/me/sessions", sent.single())
        assertEquals(listOf("a1", "b2", "c3"), list.map { it.id })
        assertTrue(list[0].current)
        assertEquals("Android", list[0].family)
        assertEquals("iphone", list[0].icon)
        assertEquals("Maurice 0.5.20 · Pixel 8", list[0].detail)
        assertEquals("iPhone", list[1].family)
        assertEquals("Carnet 1.4 (212) · iPhone17,1", list[1].detail)
        assertNull(list[1].lastUsedAt)
        assertNull(list[2].family)
        assertEquals("questionmark.circle", list[2].icon)
        assertNull(list[2].detail)
    }

    @Test fun signingOneOutAndAllTheOthers() = runTest {
        api(body = """{"ok":true}""").closeSession("b2")
        assertEquals("DELETE /api/me/sessions/b2", sent.last())
        // Already closed from elsewhere: not an error.
        api(HttpStatusCode.NotFound, """{"error":"Not found"}""").closeSession("b2")
        assertFailsWith<ApiError> { api(HttpStatusCode.Forbidden, """{"error":"session_required","code":"session_required"}""").closeSession("b2") }
        assertEquals(2, api(body = """{"closed":2}""").closeOtherSessions())
        assertEquals("POST /api/me/sessions/close-others", sent.last())
    }
}
