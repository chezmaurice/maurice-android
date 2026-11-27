package eu.chezmaurice.parity

import eu.chezmaurice.carnet.reading.ArticleCache
import eu.chezmaurice.carnet.reading.BookCache
import eu.chezmaurice.carnet.reading.formatSize
import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.Chapter
import eu.chezmaurice.core.carnet.TextStyle
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class ReadingCachesParity {
    private fun article(e: JsonElement?) = MauriceJson.decodeFromString(Article.serializer(), (e ?: JsonObject(emptyMap())).toString())
    private fun baseUrl(household: String?) = if (household == "other") Parity.baseUrl + "/other" else Parity.baseUrl

    @Test fun readingCaches() = Parity.check("carnet", "reading-caches") { c ->
        val api = ReadingFixture.api()
        when (c.string("op")) {
            "article" -> {
                c.obj("keep")?.let { keep ->
                    val member = keep["member"]?.jsonPrimitive?.content ?: "u1"
                    val styles = MauriceJson.decodeFromString(ListSerializer(TextStyle.serializer()), (keep["styles"] ?: JsonArray(emptyList())).toString())
                    ArticleCache(ReadingFixture.scope(member), article(keep["article"])).keep(keep["text"]?.jsonPrimitive?.content ?: "", styles)
                }
                val read = c.obj("read")!!
                val cache = ArticleCache(ReadingFixture.scope(read["member"]?.jsonPrimitive?.content ?: "u1"), article(read["article"]))
                mapOf("text" to cache.text(), "styles" to cache.styles().map { mapOf("start" to it.start, "end" to it.end, "kind" to it.kind) })
            }
            "book" -> {
                for (k in c.array("keep").orEmpty().map { it.jsonObject }) {
                    val cache = BookCache(baseUrl(k["household"]?.jsonPrimitive?.content), k["bookId"]!!.jsonPrimitive.int)
                    val slug = k["slug"]!!.jsonPrimitive.content
                    val text = k["text"]!!.jsonPrimitive.content
                    if (k["kind"]?.jsonPrimitive?.content == "summary") cache.keepSummary(slug, text) else cache.keepText(slug, text)
                }
                val read = c.obj("read")!!
                // A book is the household's: whoever reads (readAs) opens the same cache.
                val cache = BookCache(baseUrl(read["household"]?.jsonPrimitive?.content), read["bookId"]!!.jsonPrimitive.int)
                val slug = read["slug"]!!.jsonPrimitive.content
                if (c.bool("remove") == true) cache.remove()
                mapOf("text" to cache.text(slug), "summary" to cache.summary(slug), "cached" to cache.isCached(slug), "downloaded" to cache.isDownloaded)
            }
            "download" -> {
                val chapters = MauriceJson.decodeFromString(ListSerializer(Chapter.serializer()), c.text("chapters"))
                val cache = BookCache(api.baseUrl, 7)
                var last = listOf<Int>()
                var reports = 0
                val thrown = try {
                    cache.download(CalibreApi(api), chapters) { done, total -> last = listOf(done, total); reports++ }
                    false
                } catch (e: Throwable) { if (e is AssertionError) throw e; true }
                val requests = (Parity.requests() as JsonArray).map { it.jsonObject }
                    .sortedBy { it["path"]?.jsonPrimitive?.content.orEmpty() }
                    .map { mapOf("method" to it["method"], "path" to it["path"], "auth" to it["auth"]) }
                val size = cache.size
                mapOf("requests" to requests, "thrown" to thrown, "lastProgress" to last, "progressReports" to reports,
                    "downloaded" to cache.isDownloaded,
                    "keptChapters" to cache.chapters.orEmpty().map { it.slug },
                    "size" to size, "sizeText" to formatSize(size),
                    "texts" to chapters.map { mapOf("slug" to it.slug, "text" to cache.text(it.slug), "summary" to cache.summary(it.slug)) })
            }
            else -> mapOf("unknown op" to c.string("op"))
        }
    }
}
