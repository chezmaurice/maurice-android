package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.Positions
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test

class ReadingPositionsParity {
    private val book = 7

    private fun rec(d: JsonObject) = Positions.Record(
        chapterIndex = d["chapterIndex"]?.jsonPrimitive?.intOrNull ?: 0,
        chapterSlug = d["chapterSlug"]?.jsonPrimitive?.content ?: "",
        lastView = d["lastView"]?.jsonPrimitive?.content ?: "full",
        fullChar = d["fullChar"]?.jsonPrimitive?.intOrNull ?: 0,
        summaryPara = d["summaryPara"]?.jsonPrimitive?.intOrNull ?: 0,
        updatedAt = ReadingObs.epochMillis(d["updatedAt"]?.jsonPrimitive?.content) ?: System.currentTimeMillis(),
    )

    @Test fun readingPositions() = Parity.check("carnet", "reading-positions") { c ->
        val api = ReadingFixture.api()
        val positions = ReadingFixture.navigator().positions
        when (c.string("op")) {
            "reconcile" -> {
                c.obj("local")?.let { positions.save(book, rec(it)) }
                val raw = c.string("raw") ?: c.input.let { (it as JsonObject)["server"] }?.takeUnless { it is JsonNull }?.toString() ?: "null"
                Parity.reset(buildJsonArray {
                    add(buildJsonObject {
                        put("method", "GET"); put("path", "/api/v1/calibre/books/$book/reading-progress")
                        put("status", c.int("status") ?: 200); put("text", raw)
                        put("headers", buildJsonObject { put("content-type", "application/json") })
                    })
                })
                val progress = runCatching { CalibreApi(api).progress(book) }.getOrNull()
                val restored = positions.reconcile(book, progress)
                mapOf("restored" to ReadingObs.record(restored), "stored" to ReadingObs.record(positions.get(book)))
            }
            "members" -> {
                positions.save(book, rec(c.obj("save")!!))
                mapOf(
                    "u1" to ReadingObs.record(positions.get(book)),
                    "u2" to ReadingObs.record(ReadingFixture.navigator("u2").positions.get(book)),
                    "u1Again" to ReadingObs.record(ReadingFixture.navigator("u1").positions.get(book)),
                    "otherHousehold" to ReadingObs.record(ReadingFixture.navigator("u1", household = "other-household").positions.get(book)),
                )
            }
            "clear" -> {
                positions.save(book, rec(c.obj("save")!!))
                positions.save(8, rec(c.obj("save")!!))
                positions.clear(book)
                mapOf("cleared" to ReadingObs.record(positions.get(book)), "other" to ReadingObs.record(positions.get(8)))
            }
            "paused" -> {
                val out = linkedMapOf<String, Any?>("initially" to positions.isPaused(book))
                positions.setPaused(book, true)
                out["paused"] = positions.isPaused(book)
                out["otherBook"] = positions.isPaused(8)
                out["otherMember"] = ReadingFixture.navigator("u2").positions.isPaused(book)
                out["back"] = ReadingFixture.navigator("u1").positions.isPaused(book)
                positions.setPaused(book, false)
                out["resumed"] = positions.isPaused(book)
                out
            }
            "article" -> {
                val key = c.string("key")!!
                positions.save(key, rec(c.obj("save")!!))
                mapOf("article" to ReadingObs.record(positions.get(key)), "book0" to ReadingObs.record(positions.get(0)))
            }
            else -> mapOf("unknown op" to c.string("op"))
        }
    }
}
