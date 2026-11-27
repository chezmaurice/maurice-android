package eu.chezmaurice.carnet.reading

import eu.chezmaurice.carnet.platform.Offline
import eu.chezmaurice.carnet.platform.decimalSeparator
import eu.chezmaurice.carnet.platform.offlineKey
import eu.chezmaurice.carnet.platform.stableHash
import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.Chapter
import eu.chezmaurice.core.carnet.TextStyle
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A book kept on the device (`BookCache`): each chapter's text and summary
 * as they are read, and the whole book when it is downloaded — then a
 * manifest with its chapter list, so it opens without the network. Keyed by
 * household and book: the library is the household's, not one member's.
 */
class BookCache(baseUrl: String, private val bookId: Int) {
    private val prefix = "book-" + stableHash("$baseUrl#$bookId")
    private fun full(slug: String) = "$prefix-full-${stableHash(slug)}"
    private fun sumKey(slug: String) = "$prefix-sum-${stableHash(slug)}"
    private val manifestKey = "$prefix-manifest"

    @Serializable private data class Manifest(val chapters: List<Chapter>, val sizeBytes: Long)

    private fun manifest(): Manifest? = Offline.read(manifestKey)?.let { runCatching { MauriceJson.decodeFromString<Manifest>(it) }.getOrNull() }

    /** Downloaded whole: what "available offline" means. */
    val isDownloaded: Boolean get() = Offline.exists(manifestKey)
    /** What the download wrote (its manifest says); nothing until it is downloaded whole. */
    val size: Long get() = manifest()?.sizeBytes ?: 0
    /** The chapter list as it was at download time. */
    val chapters: List<Chapter>? get() = manifest()?.chapters

    fun text(slug: String): String? = Offline.read(full(slug))
    fun summary(slug: String): String? = Offline.read(sumKey(slug))
    fun keepText(slug: String, text: String) = Offline.write(full(slug), text)
    fun keepSummary(slug: String, text: String) = Offline.write(sumKey(slug), text)
    fun isCached(slug: String): Boolean = Offline.exists(full(slug)) || Offline.exists(sumKey(slug))
    fun remove() = Offline.deleteAll(prefix)

    /** Every chapter's text, and its summary when it has one; six at a time, each retried once. */
    suspend fun download(api: CalibreApi, chapters: List<Chapter>, onProgress: (Int, Int) -> Unit) {
        val gate = Semaphore(6)
        var done = 0
        val written = coroutineScope {
            chapters.map { ch ->
                async {
                    gate.withPermit {
                        val text = try { api.chapter(bookId, ch.slug).text } catch (e: CancellationException) { throw e } catch (_: Throwable) { api.chapter(bookId, ch.slug).text }
                        keepText(ch.slug, text)
                        // UTF-8 bytes, as the files hold them.
                        var bytes = text.encodeToByteArray().size.toLong()
                        if (ch.hasSummary) runCatching { api.chapterSummary(bookId, ch.slug).summary }.getOrNull()?.let { keepSummary(ch.slug, it); bytes += it.encodeToByteArray().size }
                        onProgress(++done, chapters.size)
                        bytes
                    }
                }
            }.awaitAll()
        }
        Offline.write(manifestKey, MauriceJson.encodeToString(Manifest.serializer(), Manifest(chapters, written.sum())))
    }
}

/**
 * An article's captured text, kept under its revision (a new capture is simply
 * not found) and under the member who saved it ([scope], `household#member`).
 */
class ArticleCache(scope: String, article: Article) {
    private val key = offlineKey(scope, "article-" + stableHash(article.id + article.textRev?.let { "#$it" }.orEmpty()))
    fun text(): String? = Offline.read(key)
    fun styles(): List<TextStyle> = Offline.read("$key-styles")?.let { runCatching { MauriceJson.decodeFromString(ListSerializer(TextStyle.serializer()), it) }.getOrNull() }.orEmpty()
    fun keep(text: String, styles: List<TextStyle>) {
        Offline.write(key, text)
        Offline.write("$key-styles", MauriceJson.encodeToString(ListSerializer(TextStyle.serializer()), styles))
    }
}

/**
 * A size as iOS's `ByteCountFormatter` (`.file` style) writes it in the app's
 * [language]: decimal units, whole kilobytes, one decimal for megabytes, two
 * beyond, rounded half up, trailing zeros dropped, the language's own words
 * ("Zero KB", "1 byte", "2 KB", "12.3 MB"; French "Zéro ko", "1,5 Go") and
 * the device's [decimal] separator (its region's, as on iOS).
 */
fun formatSize(bytes: Long, language: String = "en", decimal: Char = decimalSeparator()): String {
    val w = SIZE_WORDS[language] ?: SIZE_WORDS.getValue("en")
    if (bytes == 0L) return w.zero
    val sign = if (bytes < 0) "-" else ""
    val n = kotlin.math.abs(bytes)
    if (n < 1000) return "$sign$n ${if (n == 1L) w.byte else w.bytes}"
    var unit = 1
    while (unit < w.units.lastIndex && n >= pow10(3 * (unit + 1))) unit++
    fun decimals(u: Int) = when (u) { 1 -> 0; 2 -> 1; else -> 2 }
    fun scaled(u: Int): Long { val step = pow10(3 * u - decimals(u)); return (n + step / 2) / step }
    var q = scaled(unit)
    // 999.95 MB rounds to 1 000 MB: Foundation says 1 GB.
    if (q >= 1000 * pow10(decimals(unit)) && unit < w.units.lastIndex) { unit++; q = scaled(unit) }
    val d = decimals(unit)
    val whole = q / pow10(d)
    val fraction = (q % pow10(d)).toString().padStart(d, '0').trimEnd('0')
    return sign + whole + (if (fraction.isEmpty()) "" else "$decimal$fraction") + " " + w.units[unit]
}

private fun pow10(e: Int): Long { var p = 1L; repeat(e) { p *= 10 }; return p }

private class SizeWords(val zero: String, val byte: String, val bytes: String, val units: List<String>)

private val SI_UNITS = listOf("bytes", "KB", "MB", "GB", "TB", "PB")

/** Foundation's words, read off `ByteCountFormatter` (macOS 27) in each of the app's languages. */
private val SIZE_WORDS = mapOf(
    "en" to SizeWords("Zero KB", "byte", "bytes", SI_UNITS),
    "fr" to SizeWords("Zéro ko", "octet", "octets", listOf("octets", "ko", "Mo", "Go", "To", "Po")),
    "de" to SizeWords("0 KB", "Byte", "Byte", SI_UNITS),
    "es" to SizeWords("0 KB", "byte", "bytes", SI_UNITS),
    "it" to SizeWords("Zero KB", "byte", "byte", SI_UNITS),
    "nl" to SizeWords("0 KB", "byte", "bytes", SI_UNITS),
    "pt" to SizeWords("Zero KB", "byte", "bytes", SI_UNITS),
)
