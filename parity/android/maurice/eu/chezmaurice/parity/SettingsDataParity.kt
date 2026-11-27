package eu.chezmaurice.parity

import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.settings.formatBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test

/** Settings › Files, Import my conversations, My data (lot Settings). */
class SettingsDataParity {
    @Test fun settingsData() = Parity.check("maurice", "settings-data") { c ->
        when (c.string("kind")) {
            "bytes" -> formatBytes((c["n"] as JsonPrimitive).long)
            "library" -> {
                serve("/api/files", c)
                val lib = api().library() ?: return@check "unreadable"
                mapOf(
                    "folders" to lib.folders.map { mapOf("id" to it.id, "parentId" to it.parentId, "name" to it.name) },
                    "files" to lib.files.map { mapOf("id" to it.id, "folderId" to it.folderId, "name" to it.name, "kind" to it.kind, "size" to formatBytes(it.sizeBytes)) },
                    "summary" to mapOf("count" to lib.summary.count, "size" to formatBytes(lib.summary.sizeBytes), "quota" to formatBytes(lib.summary.quotaBytes)),
                )
            }
            "events" -> {
                serve("/api/me/events", c)
                val events = api().accountEvents() ?: return@check "unreadable"
                events.map { mapOf("id" to it.id, "kind" to it.kind, "createdAt" to it.createdAt, "seenAt" to it.seenAt) }
            }
            "imports" -> {
                // As the two cards load them (ImportCard: importHistory(provider)), Claude's first.
                val api = api()
                val histories = listOf("anthropic", "chatgpt").associateWith { api.importHistory(it) }
                buildMap<String, Any?> {
                    put("requests", SettingsSupport.requests("/api/import"))
                    for ((p, h) in histories) put(p, h?.let {
                        mapOf(
                            "provider" to it.provider, "watermark" to it.watermark,
                            "history" to it.history.map { r -> mapOf("id" to r.id, "provider" to r.provider, "conversations" to r.conversations, "messages" to r.messages, "ranAt" to r.ranAt, "status" to r.status) },
                        )
                    })
                }
            }
            else -> mapOf("unknown kind" to c.string("kind"))
        }
    }

    private fun api() = SettingsApi(MauriceFixture.session().api()!!)

    private suspend fun serve(path: String, c: ParityCase) {
        Parity.reset(JsonArray(listOf(buildJsonObject {
            put("method", "GET"); put("path", path); put("json", c["json"] ?: JsonNull); put("status", c.int("status") ?: 200)
        })))
    }
}
