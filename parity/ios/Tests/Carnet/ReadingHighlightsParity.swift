import XCTest

@MainActor
final class ReadingHighlightsParity: XCTestCase {
    func testReadingHighlights() async {
        await Parity.run("carnet", "reading-highlights") { c in
            ReadingFixture.session()
            let book = c.int("bookId") ?? 7
            return await ReadingObs.exchange {
                switch c.string("op") {
                case "book.list":
                    return try await CalibreAPI.highlights(bookId: book).map(ReadingObs.highlight)
                case "book.create":
                    return ReadingObs.highlight(try await CalibreAPI.createHighlight(
                        bookId: book, chapterSlug: c.string("slug")!, quote: c.string("quote")!, color: c.string("color")!,
                        note: c.string("note"), startOffset: c.int("start"), endOffset: c.int("end"), view: c.string("view")!))
                case "book.update":
                    return ReadingObs.highlight(try await CalibreAPI.updateHighlight(
                        bookId: book, highlightId: c.int("id")!, note: c.string("note"), color: c.string("color")))
                case "book.delete":
                    try await CalibreAPI.deleteHighlight(bookId: book, highlightId: c.int("id")!)
                    return "ok"
                case "article.list":
                    return try await GardenAPI.highlights(slug: c.string("slug")!, locale: c.string("locale")!).map(ReadingObs.highlight)
                case "article.create":
                    return ReadingObs.highlight(try await GardenAPI.createHighlight(
                        slug: c.string("slug")!, locale: c.string("locale")!, quote: c.string("quote")!, color: c.string("color")!,
                        note: c.string("note"), startOffset: c.int("start"), endOffset: c.int("end"), view: c.string("view")!))
                case "article.update":
                    return ReadingObs.highlight(try await GardenAPI.updateHighlight(
                        slug: c.string("slug")!, highlightId: c.int("id")!, note: c.string("note"), color: c.string("color")))
                case "article.delete":
                    try await GardenAPI.deleteHighlight(slug: c.string("slug")!, highlightId: c.int("id")!)
                    return "ok"
                default:
                    return ["unknown op": c.string("op") as Any]
                }
            }
        }
    }
}
