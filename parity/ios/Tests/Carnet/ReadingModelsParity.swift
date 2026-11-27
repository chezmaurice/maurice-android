import XCTest
import UIKit

@MainActor
final class ReadingModelsParity: XCTestCase {
    static func rgba(_ c: UIColor, _ style: UIUserInterfaceStyle) -> [String: Any] {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.resolvedColor(with: UITraitCollection(userInterfaceStyle: style)).getRed(&r, green: &g, blue: &b, alpha: &a)
        return ["r": Int((r * 255).rounded()), "g": Int((g * 255).rounded()), "b": Int((b * 255).rounded()), "a": Int((a * 255).rounded())]
    }

    func testReadingModels() async {
        ReadingFixture.session()
        await Parity.run("carnet", "reading-models") { c in
            do { return try Self.observe(c) } catch { return ["thrown": true] }
        }
    }

    static func observe(_ c: ParityCase) throws -> Any? {
            switch c.string("kind") {
            case "chapter":
                return ReadingObs.chapter(try ReadingObs.decode(Chapter.self, c, "json"))
            case "article":
                return ReadingObs.article(try ReadingObs.decode(Article.self, c, "json"))
            case "status":
                let s = try ReadingObs.decode(SummaryStatus.self, c, "json")
                return ["isComplete": s.isComplete]
            case "highlight":
                return ReadingObs.highlight(try ReadingObs.decode(Highlight.self, c, "json"))
            case "gardenImage":
                // The route, without the household's origin: the port is the run's.
                return gardenImageURL(c.string("image"))?.absoluteString.replacingOccurrences(of: CarnetAPI.baseURL, with: "")
            case "palette":
                let color = HighlightPalette.uiColor(c.string("key")!)
                return ["light": Self.rgba(color, .light), "dark": Self.rgba(color, .dark)]
            case "fontSizes":
                let start = ReaderFontSize.medium
                return ["sizes": ReaderFontSize.allCases.map { Double($0.body) },
                        "default": start.rawValue,
                        "cycle": [start.next.rawValue, start.next.next.rawValue, start.next.next.next.rawValue]]
            case "formatSize":
                return BookCache.formatSize(c.int("bytes")!)
            default:
                return ["unknown kind": c.string("kind") as Any]
            }
    }
}
