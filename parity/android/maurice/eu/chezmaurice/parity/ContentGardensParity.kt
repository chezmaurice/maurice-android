package eu.chezmaurice.parity

import androidx.compose.ui.platform.UriHandler
import eu.chezmaurice.core.gardens.Garden
import eu.chezmaurice.core.gardens.GardenNote
import eu.chezmaurice.core.gardens.GardensStore
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.chat.GardensHub
import eu.chezmaurice.parity.Parity.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/** Shared gardens in the sidebar (content-gardens.json). */
class ContentGardensParity {
    @Test fun contentGardens() = Parity.check("maurice", "content-gardens") { c ->
        val store = GardensStore(MauriceFixture.session().api()!!)
        when (c.string("op")) {
            "load" -> {
                store.load()
                val s = store.state.value
                mapOf("loaded" to s.loaded, "gardens" to s.gardens.map(::garden), "requests" to requests())
            }
            "labels" -> {
                // The device's members as the picker keeps them: the fixture's, and the case's.
                val members = listOf(Member("u1", "Alice", role = Role.Standard)) + c.array("members").orEmpty().map {
                    val o = it.jsonObject
                    Member(o["id"].str()!!, o["displayName"].str()!!, role = if (o["role"].str() == "guest") Role.Guest else Role.Standard)
                }
                val scope = CoroutineScope(Dispatchers.Default)
                val hub = GardensHub(store, session(members), NoLinks, scope)
                store.load()
                val out = store.state.value.gardens.map { hub.label(it, ChatSupport.strings) }
                scope.cancel()
                mapOf("labels" to out)
            }
            "access" -> {
                val a = store.noteAccess(note(c["note"] as JsonObject))
                mapOf("access" to a?.let {
                    mapOf("ownerId" to it.ownerId, "slug" to it.slug, "title" to it.title, "members" to it.members.map { m ->
                        mapOf("id" to m.memberId, "name" to m.displayName, "tends" to m.tends, "owner" to m.isOwner, "self" to m.isSelf)
                    })
                }, "requests" to requests())
            }
            "share" -> {
                store.share(note(c["note"] as JsonObject), c.string("member").orEmpty())
                mapOf("requests" to requests(), "gardens" to store.state.value.gardens.map { it.id })
            }
            "leave" -> {
                store.leave(note(c["note"] as JsonObject))
                mapOf("requests" to requests(), "gardens" to store.state.value.gardens.map { it.id })
            }
            "theme" -> {
                store.load()
                val g = store.state.value.gardens[c.int("garden") ?: 0]
                store.setTheme(c.string("theme").orEmpty(), g)
                mapOf("requests" to requests())
            }
            else -> mapOf("unknown op" to c.string("op"))
        }
    }

    private suspend fun requests() = ChatSupport.log().map { mapOf("method" to it["method"], "path" to it["path"], "body" to it["body"]) }

    private fun note(o: JsonObject): GardenNote = MauriceJson.decodeFromJsonElement(GardenNote.serializer(), o)

    private fun garden(g: Garden): Map<String, Any?> = mapOf(
        "id" to g.id, "mine" to g.mine, "members" to g.members.map { it.memberId }, "webTheme" to g.webTheme, "webPath" to g.webPath,
        "notes" to g.notes.map { mapOf("id" to it.id, "isNote" to it.isNote, "unreviewed" to it.isUnreviewed, "updatedAt" to it.updatedAt) },
    )

    /** A Session on the fake household whose device knows [members] (as `updateDeviceUsers`). */
    private fun session(members: List<Member>): Session {
        val kv = MauriceFixture.store()
        val household = Household(
            id = MauriceFixture.HOUSEHOLD_ID, serverUrl = Parity.baseUrl, name = MauriceFixture.HOUSEHOLD_NAME,
            members = members, activeMemberId = "u1",
        )
        kv.put("maurice.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(household)))
        return Session(kv, "maurice")
    }

    private object NoLinks : UriHandler {
        override fun openUri(uri: String) {}
    }
}
