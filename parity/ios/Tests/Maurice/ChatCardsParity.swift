import XCTest

/// The tally under a reply, its sources, the review footer, the server's dates
/// (parity/vectors/maurice/chat-cards.json).
@MainActor
final class ChatCardsParity: XCTestCase {
    func testChatCards() async {
        await Parity.run("maurice", "chat-cards") { c in
            let blocks = ChatSupport.blocks(c.array("blocks"))
            switch c.string("op") ?? "" {
            case "tally":
                let steps = (c.array("steps") ?? []).compactMap { $0 as? [String: Any] }.map {
                    ToolStep(tool: $0["tool"] as? String ?? "", count: ($0["count"] as? NSNumber)?.intValue ?? 1,
                             running: ($0["running"] as? NSNumber)?.boolValue ?? false)
                }
                let t = TurnTally(steps: steps, blocks: blocks, doneOnly: c.bool("doneOnly") ?? false)
                return ["web": t.web, "memory": t.memory, "tools": t.tools, "empty": t.isEmpty, "text": t.text]
            case "sources":
                return TurnSources.byOrigin(blocks).map { group -> [String: Any] in
                    ["origin": group.origin,
                     "results": TurnSources.distinct(group.payloads).map { ["title": $0.string("title"), "url": $0.string("url")] }]
                }
            case "footer":
                return ["shown": withoutReviewFooter(c.string("content") ?? "", blocks: blocks)]
            case "date":
                return ["date": parseServerDate(c.string("text") ?? "") as Any]
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }
}
