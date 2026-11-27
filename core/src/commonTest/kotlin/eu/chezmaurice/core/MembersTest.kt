package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.settings.SettingsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The admin's member sheet and the name of this Maurice (`SettingsView.swift`, 8f15efb). */
class MembersTest {
    private val sent = mutableListOf<String>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(answer: String, status: HttpStatusCode = HttpStatusCode.OK) = SettingsApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        val body = (request.body as? TextContent)?.text.orEmpty()
        sent += "${request.method.value} ${request.url.encodedPath} $body".trim()
        respond(answer, status, json)
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    @Test fun theAdminRenamesAMemberAndChangesTheirColour() = runTest {
        val kept = api("""{"id":"u2","username":"nadia","display_name":"Nadia","avatar_color":"#3d6b4f","has_pin":true,"role":"standard"}""")
            .updateMember("u2", "Nadia", "#3d6b4f")
        assertEquals("""PATCH /api/users/u2 {"display_name":"Nadia","avatar_color":"#3d6b4f"}""", sent.single())
        assertEquals("Nadia", kept.displayName)
        assertEquals("#3d6b4f", kept.avatarColor)
    }

    @Test fun aRefusedNameSaysWhy() = runTest {
        val e = assertFailsWith<ApiError> { api("""{"error":"Name too long"}""", HttpStatusCode.BadRequest).updateMember("u2", "x".repeat(80), "#2c5aa0") }
        assertEquals("[400] Name too long", e.shown)
    }

    @Test fun aMemberIsDeleted() = runTest {
        api("""{"ok":true}""").deleteMember("u3")
        assertEquals("DELETE /api/users/u3", sent.single())
    }

    @Test fun thisMauriceIsRenamedAsTheServerKeptIt() = runTest {
        val name = api("""{"name":"Chez nous","icon":"house","color":"#2c5aa0"}""").renameHousehold("Chez nous ")
        assertEquals("""PATCH /api/admin/settings {"name":"Chez nous "}""", sent.single())
        assertEquals("Chez nous", name)
    }
}
