package eu.chezmaurice.parity

import eu.chezmaurice.core.net.ApiClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.fail

/**
 * The Android half of the parity suite. [check] runs every case of a domain
 * (`parity/vectors/<app>/<domain>.json`) through the Android code and
 * compares what it observes with what the iOS code observed on the same case
 * (`parity/golden/<app>/<domain>.json`, written by parity/ios). The iOS app
 * is the reference: a difference fails the test, case by case.
 *
 * A case may carry `"accepted": {"kind": "<kind>", "why": "<why>"}`: a
 * difference classified and kept, reported rather than failed. The kinds
 * ([KINDS]): `lenient` — Android reads leniently what iOS rejects (11 Nov
 * 2026: kept on purpose); `wire` — a request detail no member or server sees
 * a difference in; `ios-bug` — iOS is wrong, Android right; `platform` — a
 * deliberate platform difference (parity.md); `not-ported` — an iOS feature
 * Android does not have yet (scripts/ios-delta.sh); `latent` — a difference
 * no input reaches today. A mark whose two sides agree again fails: drop it.
 */
class ParityCase(val name: String, val input: JsonElement, val raw: JsonObject) {
    operator fun get(key: String): JsonElement? = (input as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
    val string: String get() = (input as? JsonPrimitive)?.content ?: ""
    fun string(key: String): String? = (get(key) as? JsonPrimitive)?.content
    fun int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    fun bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    fun obj(key: String): JsonObject? = get(key) as? JsonObject
    fun array(key: String): JsonArray? = get(key) as? JsonArray
    /** A sub-value as JSON text, to feed a deserializer — a string is passed as is. */
    fun text(key: String? = null): String {
        val v = if (key == null) input else get(key) ?: JsonNull
        return if (v is JsonPrimitive && v.isString) v.content else v.toString()
    }
}

object Parity {
    init {
        // The language both halves compare in (sim-xctest.sh passes the same to iOS).
        val lang = System.getenv("PARITY_LANG") ?: "en"
        val region = if (lang == "en") "US" else lang.uppercase()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("$lang-$region"))
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(System.getenv("PARITY_TZ") ?: "Europe/Paris"))
    }

    val root: File = run {
        System.getenv("PARITY_ROOT")?.let { return@run File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "parity/vectors").isDirectory) dir = dir.parentFile
        File(dir ?: error("parity/ not found above ${System.getProperty("user.dir")}"), "parity")
    }

    val port: String = System.getenv("PARITY_PORT") ?: "47800"
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    val KINDS = setOf("lenient", "wire", "ios-bug", "platform", "not-ported", "latent")

    fun check(app: String, domain: String, body: suspend (ParityCase) -> Any?) = runBlocking {
        val vectors = Json.parseToJsonElement(File(root, "vectors/$app/$domain.json").readText()).jsonObject
        val goldenFile = File(root, "golden/$app/$domain.json")
        if (!goldenFile.exists()) fail("no iOS golden for $app/$domain: run scripts/parity.sh --ios")
        val golden = Json.parseToJsonElement(goldenFile.readText()).jsonObject["results"]!!.jsonObject

        val observed = linkedMapOf<String, JsonElement>()
        val failures = mutableListOf<String>()
        val full = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (c in vectors["cases"]!!.jsonArray.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val case = ParityCase(name, c["input"] ?: JsonNull, c)
            c["routes"]?.let { reset(it) }
            val got = try {
                toJson(body(case))
            } catch (e: Throwable) {
                if (e is AssertionError) throw e
                buildJsonObject { put("thrown", e.toString()) }
            }
            observed[name] = got
            val want = golden[name]
            val mark = c["accepted"]
            val accepted: String? = when (mark) {
                null, is JsonNull -> null
                is JsonObject -> (mark["kind"] as? JsonPrimitive)?.content ?: "?"
                else -> "?"
            }
            if (accepted != null && accepted !in KINDS) failures += "· $name: accepted without a known kind (${KINDS.joinToString()})"
            val why = ((mark as? JsonObject)?.get("why") as? JsonPrimitive)?.content.orEmpty()
            when {
                want == null -> failures += "· $name: not in the iOS golden — run scripts/parity.sh --ios"
                else -> {
                    val diffs = diff(want, got, "")
                    if (diffs.isNotEmpty() && accepted == null) {
                        full += "· $name\n" + diffs.joinToString("\n") { "    $it" }
                        failures += "· $name\n" + diffs.take(12).joinToString("\n") { "    $it" } +
                            (if (diffs.size > 12) "\n    … ${diffs.size - 12} more" else "")
                    } else if (diffs.isNotEmpty()) {
                        notes += "$accepted\t$name\t$why"
                    } else if (accepted != null) {
                        failures += "· $name: marked accepted ($accepted) but the two now agree — drop the mark"
                    }
                }
            }
        }
        // What Android observed, beside the golden, for a diff by eye.
        File(root, "build/android/$app/$domain.json").apply { parentFile.mkdirs() }
            .writeText(json.encodeToString(JsonElement.serializer(), JsonObject(observed)) + "\n")
        // Every difference, untruncated (the failure message keeps 12 lines a case).
        File(root, "build/android/$app/$domain.diff.txt").apply {
            if (full.isEmpty()) delete() else writeText(full.joinToString("\n") + "\n")
        }
        // The accepted differences, by kind, for scripts/parity.sh to count.
        File(root, "build/android/$app/$domain.accepted.tsv").apply {
            if (notes.isEmpty()) delete() else writeText(notes.joinToString("\n") + "\n")
        }
        if (failures.isNotEmpty()) fail("[$app/$domain] ${failures.size} case(s) differ from iOS:\n" + failures.joinToString("\n"))
    }

    /** Where [got] departs from [want], as `path: ios ≠ android` lines. Numbers compare by value. */
    fun diff(want: JsonElement, got: JsonElement, path: String): List<String> {
        val at = path.ifEmpty { "$" }
        return when {
            want is JsonObject && got is JsonObject -> (want.keys + got.keys).sorted().flatMap { k ->
                val w = want[k] ?: JsonNull
                val g = got[k] ?: JsonNull
                diff(w, g, "$path.$k")
            }
            want is JsonArray && got is JsonArray ->
                if (want.size != got.size) listOf("$at: ${want.size} items on iOS ≠ ${got.size} on Android — ios ${short(want)} / android ${short(got)}")
                else want.indices.flatMap { diff(want[it], got[it], "$path[$it]") }
            want is JsonPrimitive && got is JsonPrimitive && !want.isString && !got.isString &&
                want.doubleOrNull != null && got.doubleOrNull != null ->
                if (want.doubleOrNull == got.doubleOrNull) emptyList() else listOf("$at: ios ${short(want)} ≠ android ${short(got)}")
            want == got -> emptyList()
            else -> listOf("$at: ios ${short(want)} ≠ android ${short(got)}")
        }
    }

    private fun short(e: JsonElement): String = e.toString().let { if (it.length > 160) it.take(157) + "…" else it }

    /** Kotlin values (maps, lists, primitives, JsonElement, null) as JSON. */
    fun toJson(v: Any?): JsonElement = when (v) {
        null, Unit -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Enum<*> -> JsonPrimitive(v.name)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        is Array<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    // The fake household (parity/server/fake-household.ts).

    private val http by lazy { ApiClient.defaultHttpClient() }

    suspend fun reset(routes: JsonElement) {
        http.post("$baseUrl/__parity/reset") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("routes", routes) }.toString())
        }
    }

    suspend fun requests(): JsonElement =
        runCatching { Json.parseToJsonElement(http.get("$baseUrl/__parity/log").bodyAsText()) }
            .getOrElse { JsonArray(listOf(JsonPrimitive("unreachable"))) }

    /** The observation every network case returns: what was sent, what came of it. */
    suspend fun exchange(body: suspend () -> Any?): Map<String, Any?> {
        val result: Any? = try { toJson(body()) } catch (e: Throwable) {
            if (e is AssertionError) throw e
            mapOf("thrown" to errorKind(e))
        }
        return mapOf("requests" to requests(), "result" to result)
    }

    /** An error reduced to what both platforms can say: the HTTP status if there is one, else "error". */
    fun errorKind(e: Throwable): String {
        val status = (e as? eu.chezmaurice.core.net.ApiError)?.status
            ?: Regex("""\b[1-5]\d\d\b""").find(e.toString())?.value?.toInt()
        return if (status != null) "http $status" else "error"
    }

    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull
}
