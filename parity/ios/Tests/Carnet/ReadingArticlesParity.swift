import XCTest

@MainActor
final class ReadingArticlesParity: XCTestCase {
    func testReadingArticles() async {
        await Parity.run("carnet", "reading-articles") { c in
            ReadingFixture.session()
            return await ReadingObs.exchange {
                switch c.string("op") {
                case "article":
                    let a = try await GardenAPI.article(slug: c.string("slug")!, locale: c.string("locale")!).article
                    var o = ReadingObs.article(a)
                    o["sharedBy"] = a.sharedBy.map { ["username": $0.username, "displayName": $0.displayName as Any] } as Any
                    return o
                case "text":
                    let t = try await GardenAPI.articleText(slug: c.string("slug")!, locale: c.string("locale")!)
                    return ["text": t.text, "title": t.title as Any,
                            "styles": (t.styles ?? []).map { ["start": $0.start, "end": $0.end, "kind": $0.kind] }]
                default:
                    return ["unknown op": c.string("op") as Any]
                }
            }
        }
    }
}
