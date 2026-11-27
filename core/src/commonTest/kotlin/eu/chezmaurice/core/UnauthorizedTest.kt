package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A 401 ends the member's session, except one that refuses a password typed to confirm (chezmaurice/maurice-android#2). */
class UnauthorizedTest {
    private fun client(body: String, ended: () -> Unit) = ApiClient(
        "https://maison.test", "tok",
        HttpClient(MockEngine { respond(body, HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = false
            install(ContentNegotiation) { json(MauriceJson) }
        },
        ended,
    )

    @Test fun aWrongCurrentPasswordLeavesTheAdminSignedIn() = runTest {
        var ended = 0
        val e = assertFailsWith<ApiError> {
            client("""{"error":"The current password is not right","code":"password_wrong"}""") { ended++ }.send(HttpMethod.Post, "/api/auth/password")
        }
        assertEquals("password_wrong", e.code)
        assertEquals(0, ended)
    }

    @Test fun anyOther401EndsTheSession() = runTest {
        var ended = 0
        assertFailsWith<ApiError> { client("""{"error":"Unauthorized"}""") { ended++ }.send(HttpMethod.Get, "/api/users/me") }
        assertEquals(1, ended)
    }
}
