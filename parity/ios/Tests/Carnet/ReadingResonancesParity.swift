import XCTest

@MainActor
final class ReadingResonancesParity: XCTestCase {
    func testReadingResonances() async {
        await Parity.run("carnet", "reading-resonances") { c in
            ReadingFixture.session()
            return await ReadingObs.exchange {
                switch c.string("op") {
                case "targets":
                    return try await GardenAPI.linkTargets(query: c.string("q") ?? "").map(ReadingObs.target)
                case "create":
                    let to = try ReadingObs.decode(LinkTarget.self, c, "to")
                    let s = c.dict("source") ?? [:]
                    let src = ResonanceSource(label: s["label"] as? String ?? "",
                                              basename: s["basename"] as? String, bookTitle: s["bookTitle"] as? String)
                    // As HighlightEditorSheet.sendResonanceIfLinked / ResonanceSheet.send call it.
                    try await GardenAPI.createResonance(
                        to: to, comment: c.string("comment") ?? "", quote: c.string("quote"),
                        sourceLabel: src.bookTitle == nil ? src.label : nil,
                        sourceBasename: src.basename, sourceBookTitle: src.bookTitle)
                    return "ok"
                default:
                    return ["unknown op": c.string("op") as Any]
                }
            }
        }
    }
}
