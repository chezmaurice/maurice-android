import XCTest

/// Long pastes and shared articles as cards (parity/vectors/maurice/chat-snippets.json).
@MainActor
final class ChatSnippetsParity: XCTestCase {
    func testChatSnippets() async {
        await Parity.run("maurice", "chat-snippets") { c in
            switch c.string("op") ?? "" {
            case "isLong":
                let t = c.string("text") ?? ""
                return ["isLong": PastedText.isLong(t), "lineCount": PastedText.lineCount(t)]
            case "fence":
                return ["fenced": PastedText.fence(c.string("text") ?? "", info: c.string("info") ?? "")]
            case "compose":
                let snippets: [Snippet] = (c.array("snippets") ?? []).compactMap { s in
                    guard let d = s as? [String: Any] else { return nil }
                    if let p = d["paste"] as? String { return .paste(p) }
                    if let a = d["article"] as? [String: Any] { return .article(ChatSupport.article(a)) }
                    return nil
                }
                return ["sent": PastedText.compose(c.string("typed") ?? "", pastes: snippets)]
            case "insertion":
                guard let r = PastedText.insertion(from: c.string("old") ?? "", to: c.string("new") ?? "") else { return NSNull() }
                return ["inserted": r.inserted, "remaining": r.remaining]
            case "article":
                let a = ChatSupport.article(c.dict("article") ?? [:])
                return ["fenced": a.fenced, "source": a.source, "wordCount": a.wordCount]
            case "read":
                let doc = ReplyDocument.make(c.string("content") ?? "", style: ReplyStyle(MauriceTheme.palette("cream")))
                var cards: [[String: Any]] = []
                var hasText = false
                for piece in doc.pieces {
                    switch piece {
                    case .text(let body):
                        let s = body.attributed(width: 320).string
                        if !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { hasText = true }
                    case .paste(let text):
                        cards.append(["kind": "paste", "text": text, "lines": PastedText.lineCount(text)])
                    case .article(let a):
                        cards.append(ChatSupport.articleCard(a))
                    }
                }
                return ["cards": cards, "hasText": hasText]
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }
}
