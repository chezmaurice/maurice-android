import XCTest

@MainActor
final class ReadingCalibreParity: XCTestCase {
    func testReadingCalibre() async {
        await Parity.run("carnet", "reading-calibre") { c in
            ReadingFixture.session()
            let book = c.int("bookId") ?? 7
            return await ReadingObs.exchange {
                switch c.string("op") {
                case "chapters":
                    return try await CalibreAPI.chapters(bookId: book).map(ReadingObs.chapter)
                case "chapter":
                    let t = try await CalibreAPI.chapter(bookId: book, slug: c.string("slug")!)
                    return ["title": t.title, "text": t.text, "wordCount": t.wordCount as Any]
                case "summary":
                    let s = try await CalibreAPI.chapterSummary(bookId: book, slug: c.string("slug")!)
                    return ["exists": s.exists, "title": s.title as Any, "summary": s.summary as Any]
                case "progress":
                    return ReadingObs.progress(try await CalibreAPI.readingProgress(bookId: book))
                case "setProgress":
                    try await CalibreAPI.setReadingProgress(
                        bookId: book, chapterIndex: c.int("chapterIndex")!, chapterSlug: c.string("chapterSlug")!,
                        view: c.string("view")!, position: (c["position"] as? NSNumber)?.doubleValue ?? 0)
                    return "ok"
                case "tracking":
                    try await CalibreAPI.setReadingTracking(bookId: book, paused: c.bool("paused")!)
                    return "ok"
                case "reset":
                    try await CalibreAPI.resetReadingProgress(bookId: book)
                    return "ok"
                case "bookmarks":
                    return try await CalibreAPI.bookmarks(bookId: book).map {
                        ["id": $0.id, "chapterSlug": $0.chapterSlug, "view": $0.view, "note": $0.note as Any]
                    }
                case "toggleBookmark":
                    let r = try await CalibreAPI.toggleBookmark(bookId: book, chapterSlug: c.string("slug")!, view: c.string("view")!)
                    return ["bookmarked": r.bookmarked, "bookmarkId": r.bookmark?.id as Any, "note": r.bookmark?.note as Any]
                case "bookmarkNote":
                    try await CalibreAPI.updateBookmarkNote(bookId: book, bookmarkId: c.int("id")!, note: c.string("note"))
                    return "ok"
                case "status":
                    let s = try await CalibreAPI.status(bookId: book)
                    return ["chapters": s.chapters as Any, "summarized": s.summarized as Any, "indexed": s.indexed as Any,
                            "isComplete": s.isComplete,
                            "job": s.job.map { ["action": $0.action, "state": $0.state.rawValue, "error": $0.error as Any] } as Any]
                case "summarize":
                    try await CalibreAPI.summarize(bookId: book)
                    return "ok"
                case "extract":
                    try await CalibreAPI.extract(bookId: book)
                    return "ok"
                case "follow":
                    try await DomainsAPI.createCompanion(name: c.string("title")!, bookId: book)
                    return "ok"
                default:
                    return ["unknown op": c.string("op") as Any]
                }
            }
        }
    }
}
