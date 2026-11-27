package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.CalibreApi
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class ReadingCalibreParity {
    @Test fun readingCalibre() = Parity.check("carnet", "reading-calibre") { c ->
        val calibre = CalibreApi(ReadingFixture.api())
        val book = c.int("bookId") ?: 7
        ReadingObs.exchange {
            when (c.string("op")) {
                "chapters" -> calibre.chapters(book).map(ReadingObs::chapter)
                "chapter" -> calibre.chapter(book, c.string("slug")!!).let { mapOf("title" to it.title, "text" to it.text, "wordCount" to it.wordCount) }
                "summary" -> calibre.chapterSummary(book, c.string("slug")!!).let { mapOf("exists" to it.exists, "title" to it.title, "summary" to it.summary) }
                "progress" -> ReadingObs.progress(calibre.progress(book))
                "setProgress" -> {
                    calibre.setProgress(book, c.int("chapterIndex")!!, c.string("chapterSlug")!!, c.string("view")!!, c["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                    "ok"
                }
                "tracking" -> { calibre.setTracking(book, c.bool("paused")!!); "ok" }
                "reset" -> { calibre.resetProgress(book); "ok" }
                "bookmarks" -> calibre.bookmarks(book).map { mapOf("id" to it.id, "chapterSlug" to it.chapterSlug, "view" to it.view, "note" to it.note) }
                "toggleBookmark" -> calibre.toggleBookmark(book, c.string("slug")!!, c.string("view")!!).let {
                    mapOf("bookmarked" to it.bookmarked, "bookmarkId" to it.bookmark?.id, "note" to it.bookmark?.note)
                }
                "bookmarkNote" -> { calibre.setBookmarkNote(book, c.int("id")!!, c.string("note")); "ok" }
                "status" -> calibre.status(book).let { s ->
                    mapOf("chapters" to s.chapters, "summarized" to s.summarized, "indexed" to s.indexed, "isComplete" to s.isComplete,
                        "job" to s.job?.let { mapOf("action" to it.action, "state" to it.state, "error" to it.error) })
                }
                "summarize" -> { calibre.summarize(book); "ok" }
                "extract" -> { calibre.extract(book); "ok" }
                "follow" -> { calibre.follow(book, c.string("title")!!); "ok" }
                else -> mapOf("unknown op" to c.string("op"))
            }
        }
    }
}
