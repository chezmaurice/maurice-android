import XCTest

@MainActor
final class ReadingCachesParity: XCTestCase {
    static func article(_ d: [String: Any]?) throws -> Article {
        try JSONDecoder().decode(Article.self, from: JSONSerialization.data(withJSONObject: d ?? [:]))
    }

    /// The household a step runs in: the fake one ("home"), or a second
    /// paired on the same device ("other"), as the household switcher does.
    static func household(_ s: CarnetSession, _ name: String?) {
        if name == "other" {
            if s.households.contains(where: { $0.serverURL == Parity.baseURL + "/other" }) {
                s.pair(serverURL: Parity.baseURL + "/other")
            } else {
                ReadingFixture.pairOther(s, url: Parity.baseURL + "/other")
            }
        } else {
            s.pair(serverURL: Parity.baseURL)
        }
    }

    func testReadingCaches() async {
        await Parity.run("carnet", "reading-caches") { c in
            let s = ReadingFixture.session()
            switch c.string("op") {
            case "article":
                if let keep = c.dict("keep") {
                    ReadingFixture.signIn(s, member: keep["member"] as? String ?? "u1")
                    let styles = try JSONDecoder().decode([TextStyle].self, from: JSONSerialization.data(withJSONObject: keep["styles"] ?? []))
                    ArticleCache.cacheText(try Self.article(keep["article"] as? [String: Any]), text: keep["text"] as? String ?? "", styles: styles)
                }
                let read = c.dict("read")!
                ReadingFixture.signIn(s, member: read["member"] as? String ?? "u1")
                let a = try Self.article(read["article"] as? [String: Any])
                return ["text": ArticleCache.text(a) as Any,
                        "styles": ArticleCache.styles(a).map { ["start": $0.start, "end": $0.end, "kind": $0.kind] }]
            case "book":
                for k in c.array("keep") as? [[String: Any]] ?? [] {
                    Self.household(s, k["household"] as? String)
                    let id = (k["bookId"] as? NSNumber)!.intValue, slug = k["slug"] as! String, text = k["text"] as! String
                    if k["kind"] as? String == "summary" { BookCache.cacheChapterSummary(bookId: id, slug: slug, text: text) }
                    else { BookCache.cacheChapterText(bookId: id, slug: slug, text: text) }
                }
                let read = c.dict("read")!
                Self.household(s, read["household"] as? String)
                if let who = c.string("readAs") { ReadingFixture.signIn(s, member: who) }
                let id = (read["bookId"] as? NSNumber)!.intValue, slug = read["slug"] as! String
                if c.bool("remove") == true { BookCache.remove(bookId: id) }
                return ["text": BookCache.chapterText(bookId: id, slug: slug) as Any,
                        "summary": BookCache.chapterSummary(bookId: id, slug: slug) as Any,
                        "cached": BookCache.isChapterCached(bookId: id, slug: slug),
                        "downloaded": BookCache.isDownloaded(bookId: id)]
            case "download":
                let chapters = try ReadingObs.decode([Chapter].self, c, "chapters")
                let book = Book(id: 7, title: "Livre", authors: [], tags: nil, formats: nil, series: nil, description: nil,
                                chapters: chapters.count, summarized: nil, indexed: nil, added: nil)
                var last: [Int] = []
                var reports = 0
                var thrown = false
                do {
                    try await BookCache.download(book: book, chapters: chapters) { done, total in last = [done, total]; reports += 1 }
                } catch { thrown = true }
                let requests = ((await Parity.requests()) as? [[String: Any]] ?? [])
                    .sorted { "\($0["path"] ?? "")" < "\($1["path"] ?? "")" }
                    .map { ["method": $0["method"] ?? NSNull(), "path": $0["path"] ?? NSNull(), "auth": $0["auth"] ?? NSNull()] }
                let size = BookCache.size(bookId: 7)
                return ["requests": requests, "thrown": thrown, "lastProgress": last, "progressReports": reports,
                        "downloaded": BookCache.isDownloaded(bookId: 7),
                        "keptChapters": (BookCache.cachedChapters(bookId: 7) ?? []).map(\.slug),
                        "size": size, "sizeText": BookCache.formatSize(size),
                        "texts": chapters.map { ["slug": $0.slug,
                                                 "text": BookCache.chapterText(bookId: 7, slug: $0.slug) as Any,
                                                 "summary": BookCache.chapterSummary(bookId: 7, slug: $0.slug) as Any] }]
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }
}
