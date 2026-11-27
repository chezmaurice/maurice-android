package eu.chezmaurice.core

import eu.chezmaurice.core.media.PhotoSize
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.EnrollOutcome
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.MemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new photo, taken or chosen: `PUT /api/users/:id/avatar {image: data URI}` (`SettingsView.uploadAvatar`). */
class AvatarUploadTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun thePhotoGoesAsAJpegDataUriAndTheMemberTakesItsAddress() = runTest {
        var sent: Pair<HttpMethod, String>? = null
        val http = HttpClient(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            when (request.url.encodedPath) {
                "/api/health" -> respond("""{"status":"ok","setup_complete":true,"household":"Chez nous"}""", HttpStatusCode.OK, json)
                "/api/auth/enroll" -> respond("""{"user_id":"u9","token":"tok9","role":"standard","needs_pin":true}""", HttpStatusCode.OK, json)
                "/api/users/me" -> respond("""{"id":"u9","username":"odile","display_name":"Odile","avatar_color":"#2c5aa0","has_pin":false,"role":"standard"}""", HttpStatusCode.OK, json)
                "/api/auth/set-pin" -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                "/api/users" -> respond("""[{"id":"u9","display_name":"Odile","avatar_color":"#2c5aa0","has_pin":true}]""", HttpStatusCode.OK, json)
                "/api/users/u9/avatar" -> {
                    sent = request.method to MauriceJson.parseToJsonElement(body).jsonObject["image"]!!.jsonPrimitive.content
                    respond("""{"id":"u9","display_name":"Odile","avatar_color":"#2c5aa0","avatar_url":"/api/avatars/abc.jpg","has_pin":true,"role":"standard"}""", HttpStatusCode.OK, json)
                }
                else -> respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, json)
            }
        }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) }; install(HttpTimeout) }

        val session = Session(MemoryKeyValueStore(), "maurice", http)
        session.connect("maison.test")
        session.setPin(assertIs<EnrollOutcome.NeedsPin>(session.enroll("ABCD2345", "Odile", "#2c5aa0")).pending, "1234")
        assertNull(session.state.value.activeMember?.avatarUrl)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        session.uploadAvatar(PhotoSize.dataUri(jpeg))

        val (method, image) = sent!!
        assertEquals(HttpMethod.Put, method)
        assertTrue(image.startsWith("data:image/jpeg;base64,"), "the server reads data:image/<type>;base64,…")
        assertEquals("/9j/4A==", image.substringAfter(","))
        assertEquals("/api/avatars/abc.jpg", session.state.value.activeMember?.avatarUrl)
    }
}
