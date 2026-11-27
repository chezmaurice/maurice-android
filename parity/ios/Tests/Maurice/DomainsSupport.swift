import Foundation

/// Lot C1 (domains & composer): what the domain and composer parity tests
/// read off the iOS stores, shaped as `DomainsSupport.kt` shapes it on the
/// Android side. Readers only: no logic of the app's is redone here.
@MainActor
enum DomainsSupport {
    /// A row as the member sees it: its fields, its glyph, its baked-in context.
    static func row(_ m: Maurice) -> [String: Any] {
        [
            "id": m.rawId as Any,
            "name": m.name,
            "kind": m.kind,
            "icon": m.icon as Any,
            "symbol": m.symbol,
            "model": m.model as Any,
            "temp": m.temp,
            "thinking": m.thinking as Any,
            "tagline": m.tagline,
            "prompt": m.prompt,
            "users": m.users,
            "context": m.contextItems.map(\.id),
            "weight": m.weight,
            "count": m.count,
            "toolFamilies": m.toolFamilies as Any,
            "createdBy": m.createdBy as Any,
            "isCompanion": m.isCompanion,
            "isDomainOfU1": m.isDomain(of: "u1"),
        ]
    }

    static func overview(_ o: DomainOverview) -> [String: Any] {
        [
            "kind": o.kind,
            "mine": o.mine,
            "briefUpdatedAt": o.briefUpdatedAt as Any,
            "briefByMember": o.briefByMember,
            "conversationId": o.conversationId as Any,
            // The seeded notes are not shown in the list on either side (only on the brief page: DomainsBriefParity).
        ]
    }

    static func notes(_ n: SeededNotes) -> [String: Any] {
        ["total": n.total, "unreviewed": n.unreviewed, "webPath": n.webPath as Any]
    }

    static func model(_ m: MauriceModel) -> [String: Any] {
        [
            "id": m.id, "name": m.name, "tier": m.tier, "provider": m.provider,
            "sub": m.sub, "desc": m.desc, "note": m.note,
            "available": m.available, "thinking": m.thinking,
            "isLocal": m.isLocal, "thinkingIsOptional": m.thinkingIsOptional,
        ]
    }

    static func family(_ f: ToolFamily) -> [String: Any] {
        ["id": f.id, "title": f.title, "icon": f.icon, "blurb": f.blurb, "count": f.count, "group": f.group, "alwaysOn": f.alwaysOn]
    }

    static func brief(_ b: DomainBrief?) -> Any {
        guard let b else { return NSNull() }
        return ["text": b.text, "updatedAt": b.updatedAt, "sources": b.sources, "model": b.model as Any, "byMember": b.byMember]
    }

    static func proposal(_ p: DomainProposal) -> [String: Any] {
        [
            "id": p.id, "name": p.name, "summary": p.summary, "oneLine": p.one_line as Any,
            "state": p.state, "conversations": p.conversations, "mailThreads": p.mail_threads as Any,
            "weight": p.weight, "isNew": p.is_new as Any, "isAlive": p.isAlive, "canRestore": p.canRestore,
            "domainId": p.domain_id as Any,
        ]
    }

    /// The fake household's log, keeping the entries `keep` says yes to.
    static func requests(_ keep: (String) -> Bool = { _ in true }) async -> [[String: Any]] {
        (await Parity.requests() as? [[String: Any]] ?? []).filter {
            let path = $0["path"] as? String ?? ""
            // A ChatService left over from an earlier test keeps reopening its socket.
            return !path.hasSuffix("/ws") && keep(path)
        }
    }

    /// The log without each entry's body: for requests whose body the
    /// Android side builds in a Composable, out of reach of a test.
    static func withoutBodies(_ log: [[String: Any]]) -> [[String: Any]] {
        log.map { var e = $0; e["body"] = nil; return e }
    }

    /// Waits until the household has heard nothing new for a while: the
    /// composer fires its weigh-and-save in unstructured Tasks.
    static func settle(quiet: Double = 0.35, max: Double = 8) async {
        var last = -1
        var stable = 0
        let start = Date()
        while Date().timeIntervalSince(start) < max {
            let n = (await Parity.requests() as? [Any])?.count ?? 0
            if n == last { stable += 1; if stable >= 2 { return } } else { stable = 0; last = n }
            try? await Task.sleep(for: .seconds(quiet / 2))
        }
    }

    /// A store with the rows (and overview) the case's routes serve.
    static func store() -> MauriceStore { MauriceStore(session: MauriceFixture.session()) }
}
