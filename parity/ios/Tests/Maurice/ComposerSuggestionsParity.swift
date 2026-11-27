import XCTest

@MainActor
final class ComposerSuggestionsParity: XCTestCase {
    func testComposerSuggestions() async {
        await Parity.run("maurice", "composer-suggestions") { c in
            switch c.string("op") {
            case "decode":
                // As the drawer's APIClient decodes them (a plain JSONDecoder).
                guard let rows = try? JSONDecoder().decode([EntrySuggestion].self, from: c.data("rows")) else { return ["rows": "undecodable"] }
                return ["rows": rows.map { r -> [String: Any] in
                    ["id": r.id, "kind": r.kind, "title": r.title, "year": r.year as Any, "subtitle": r.subtitle as Any,
                     "image": r.image as Any, "note": r.note, "state": r.state, "existing": r.existing, "isKept": r.isKept,
                     "candidates": r.candidates.map { ["id": $0.id, "title": $0.title, "year": $0.year as Any, "subtitle": $0.subtitle as Any] },
                     "entry": r.entry as Any, "webPath": r.web_path as Any, "query": r.query as Any, "searchable": r.searchable as Any]
                }]
            case "episodes":
                guard let l = try? JSONDecoder().decode(EpisodeList.self, from: c.data("body")) else { return ["episodes": "undecodable"] }
                return ["feed": l.feed, "suggested": l.suggested as Any,
                        "episodes": l.episodes.map { ["id": $0.id, "title": $0.title, "date": $0.date as Any, "description": $0.description,
                                                      "image": $0.image, "duration": $0.duration as Any] }]
            case "open":
                let chat = ChatService(session: MauriceFixture.session())
                await chat.loadConversations()
                let id = c.string("conversation") ?? ""
                await chat.selectConversation(id)
                await DomainsSupport.settle()
                let n = chat.suggestionCount(for: id)
                return ["requests": await DomainsSupport.requests { $0.hasPrefix("/api/suggestions") },
                        "mark": ["pending": n.pending, "kept": n.kept]]
            default:
                return NSNull()
            }
        }
    }
}
