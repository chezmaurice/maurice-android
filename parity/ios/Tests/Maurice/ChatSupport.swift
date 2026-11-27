import Foundation

/// Helpers of the Chat lot (ChatTurn, ChatThread, ChatSnippets, ChatCards,
/// ChatArticle). They read fields and rename them; no logic of the app's.
@MainActor
enum ChatSupport {
    // MARK: Values

    static func article(_ a: [String: Any]) -> SharedArticle {
        SharedArticle(title: a["title"] as? String ?? "", site: a["site"] as? String ?? "",
                      author: a["author"] as? String ?? "", date: a["date"] as? String ?? "",
                      url: a["url"] as? String ?? "", text: a["text"] as? String ?? "")
    }

    static func articleCard(_ a: SharedArticle) -> [String: Any] {
        ["kind": "article", "title": a.title, "site": a.site, "author": a.author, "date": a.date,
         "url": a.url, "text": a.text, "source": a.source, "wordCount": a.wordCount]
    }

    /// `[{tool, data}]` as the app decodes them.
    static func blocks(_ raw: [Any]?) -> [DataBlock] {
        (raw ?? []).compactMap { item in
            guard let data = try? JSONSerialization.data(withJSONObject: item, options: [.fragmentsAllowed]) else { return nil }
            return try? JSONDecoder().decode(DataBlock.self, from: data)
        }
    }

    // MARK: The household's log

    static func log() async -> [[String: Any]] {
        (await Parity.requests() as? [[String: Any]]) ?? []
    }

    /// What the server was asked to change: every request but the reads, the
    /// sockets' upgrades among them.
    static func sent(_ log: [[String: Any]]) -> [[String: Any]] {
        log.filter { ($0["method"] as? String) != "GET" }
            .map { ["method": $0["method"] ?? NSNull(), "path": $0["path"] ?? NSNull(), "body": $0["body"] ?? NSNull()] }
    }

    /// The searches and the reads of a next page, with their query. (A plain
    /// re-read of the first page is left out: iOS also does one whenever its
    /// member socket reconnects, which it does every few seconds against the
    /// fake household, which has no sockets.)
    static func listReads(_ log: [[String: Any]]) -> [[String: Any]] {
        log.filter {
            ($0["method"] as? String) == "GET" &&
            (($0["path"] as? String) == "/api/conversations/search" ||
             (($0["path"] as? String) == "/api/conversations" && ($0["query"] as? [String: Any])?["before_id"] != nil))
        }.map { ["path": $0["path"] ?? NSNull(), "query": $0["query"] ?? NSNull()] }
    }

    static func attached(_ log: [[String: Any]]) -> Bool {
        log.contains { ($0["method"] as? String) == "GET" && (($0["path"] as? String)?.hasSuffix("/turn") ?? false) }
    }

    // MARK: The chat as the member sees it

    /// The red banner, or the quieter notice where iOS has one.
    static func banner(_ chat: ChatService) -> Any { chat.error ?? chat.notice ?? NSNull() }

    static func thread(_ chat: ChatService, ids: Bool) -> [[String: Any]] {
        chat.messages.map { m in
            var d: [String: Any] = ["role": m.role, "content": m.content, "author": m.author_id ?? NSNull(),
                                    "tools": (m.data ?? []).map(\.tool), "cost": m.usage?.cost ?? NSNull()]
            if ids { d["id"] = m.id }
            return d
        }
    }

    static func list(_ chat: ChatService) -> [[String: Any]] {
        chat.conversations.map { ["id": $0.id, "title": $0.title ?? NSNull(), "maurice": $0.maurice_id ?? NSNull()] }
    }

    static func participants(_ chat: ChatService) -> [[String: Any]] {
        chat.participants.map { ["id": $0.member_id, "name": $0.display_name, "role": $0.role, "color": $0.avatar_color] }
    }

    /// The reply the streaming row typed out, once the turn is over (iOS keeps
    /// it in `finishing` while the row finishes; only a reply with words is).
    static func reply(_ chat: ChatService) -> Any {
        guard let f = chat.finishing else { return NSNull() }
        let usage = chat.messages.first { $0.id == f.id }?.usage
        return ["text": f.text, "tools": f.blocks.map(\.tool), "cost": usage?.cost ?? NSNull(), "model": usage?.model ?? NSNull()]
    }

    /// The recap of the tools each reply of the session used.
    static func trails(_ chat: ChatService) -> [String: Any] {
        chat.toolTrails.mapValues { a in a.steps.map { ["tool": $0.tool, "count": $0.count, "running": $0.running] } }
    }

    static func pause(_ ms: Int) async { try? await Task.sleep(for: .milliseconds(ms)) }
}
