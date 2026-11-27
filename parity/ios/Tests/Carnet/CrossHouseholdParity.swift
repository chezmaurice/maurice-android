import XCTest

/// Between households: giving an entry, inviting a friend as a guest, an
/// article read in its owner's notes — the iOS side of `CrossHouseholdParity.kt`.
@MainActor
final class CrossHouseholdParity: XCTestCase {

    /// The person the sheet gives to, over there.
    private static func recipient(_ c: ParityCase) -> GiveRecipient {
        GiveRecipient(
            household: Household(id: "h2", serverURL: Parity.baseURL, name: "Les Martin", deviceMembers: [], activeMemberId: nil),
            target: CrossHouseholdAPI.Target(serverURL: Parity.baseURL, token: "tok-u1"),
            member: CarnetAPI.Member(id: "u9", display_name: "Bea", avatar_color: "#6b6460", has_pin: false, role: "guest", username: "bea"),
            isSelf: c.bool("self") ?? false)
    }

    /// A refusal as the sheet tells it apart.
    private static func kind(_ error: Error) -> String {
        switch error {
        case CrossHouseholdAPI.Failure.exists(let c, let l, let s): return "exists:\(c)/\(l)/\(s)"
        case CrossHouseholdAPI.Failure.unreachable: return "unreachable"
        case CrossHouseholdAPI.Failure.offline: return "offline"
        case CrossHouseholdAPI.Failure.sessionEnded: return "sessionEnded"
        case CrossHouseholdAPI.Failure.unsupported: return "unsupported"
        case CrossHouseholdAPI.Failure.tooLarge: return "tooLarge"
        case CrossHouseholdAPI.Failure.forbidden(let code): return "forbidden:\(code ?? "nil")"
        case CrossHouseholdAPI.Failure.refused(let code): return "refused:\(code)"
        case CrossHouseholdAPI.Failure.other(let message): return "other:\(message)"
        default: return "error"
        }
    }

    private static func export(_ e: EntryExport) -> [String: Any] {
        [
            "title": e.title, "kind": e.kind as Any, "author": e.author as Any, "sourceKind": e.sourceKind as Any,
            "hasSource": e.source != nil, "hasFiche": e.fiche != nil, "prose": e.fiche?.prose as Any,
            "notes": (e.fiche?.notes ?? []).map { ["id": $0.id, "date": $0.date as Any, "author": $0.author as Any, "text": $0.text,
                                                   "quote": $0.quote as Any, "pageLabel": $0.pageLabel as Any] },
            "highlights": e.highlights.map { $0.map { ["id": $0.id, "text": $0.text, "chapter": $0.chapter as Any, "note": $0.note as Any] } } as Any,
            "card": e.card.map { ["body": $0.body, "status": $0.status, "url": $0.url as Any, "isDraft": $0.isDraft] } as Any,
            "conversations": e.conversations.map { $0.map { ["id": $0.id, "title": $0.title as Any, "date": $0.date as Any,
                                                             "messageCount": $0.message_count as Any, "summary": $0.summary as Any,
                                                             "shareable": $0.shareable, "reason": $0.reason as Any] } } as Any,
        ]
    }

    func testRequests() async {
        await Parity.run("carnet", "cross-household-requests") { c in
            ReadingFixture.session()
            let target = CrossHouseholdAPI.Target(serverURL: Parity.baseURL, token: "tok-u1")
            var result: Any
            do {
                switch c.string("op") {
                case "guestInvite":
                    let i = try await CrossHouseholdAPI.guestInvite()
                    result = ["code": i.code, "link": i.link as Any, "expiresAt": i.expires_at]
                case "guestInviteState":
                    result = await CrossHouseholdAPI.guestInviteState(c.string("code")!) as Any
                case "roster":
                    result = try await CrossHouseholdAPI.roster(target).map {
                        ["id": $0.id, "displayName": $0.display_name, "username": $0.username as Any, "role": $0.role as Any, "avatarUrl": $0.avatar_url as Any]
                    }
                case "export":
                    result = Self.export(try await CrossHouseholdAPI.export(target, collection: c.string("collection")!,
                                                                             locale: c.string("locale")!, slug: c.string("slug")!))
                case "conversation":
                    let x = try await CrossHouseholdAPI.conversation(target, id: c.string("id")!)
                    result = ["title": x.title as Any, "date": x.date as Any,
                              "messages": x.messages.map { ["role": $0.role, "author": $0.author as Any, "text": $0.text, "isMaurice": $0.isMaurice] }]
                case "import":
                    let e = try JSONDecoder().decode(EntryExport.self, from: c.data("export"))
                    let pick = c.dict("pick") ?? [:]
                    var p = ImportPayload(entry: e.entry, fromHousehold: c.string("from")!)
                    if pick["source"] as? Bool == true { p.source = e.source }
                    if pick["prose"] as? Bool == true { p.prose = e.fiche?.prose }
                    let notes = pick["notes"] as? [String] ?? []
                    p.notes = (e.fiche?.notes ?? []).filter { notes.contains($0.id) }.map(\.raw)
                    let highlights = pick["highlights"] as? [String] ?? []
                    p.highlights = (e.highlights ?? []).filter { highlights.contains($0.id) }.map(\.raw)
                    if pick["card"] as? Bool == true { p.card = e.card }
                    p.conversations = (pick["conversations"] as? [[String: Any]] ?? []).compactMap { item in
                        guard let meta = e.conversations?.first(where: { $0.id == item["id"] as? String }) else { return nil }
                        let whole = (item["whole"] as? [String: Any]).flatMap {
                            try? JSONDecoder().decode(ConversationExport.self, from: JSONSerialization.data(withJSONObject: $0))
                        }
                        return (meta, whole)
                    }
                    p.shareWith = c.string("shareWith")
                    let imported = try await CrossHouseholdAPI.importEntry(target, p)
                    result = ["ref": imported.ref]
                default:
                    result = ["unknown op": c.string("op") as Any]
                }
            } catch {
                result = ["failure": Self.kind(error), "message": GiveMessages.describe(error, recipient: Self.recipient(c))]
            }
            return ["requests": await Parity.requests(), "result": Parity.normalise(result)]
        }
    }

    func testWords() async {
        await Parity.run("carnet", "cross-household-words") { c in
            switch c.string("op") {
            case "sources":
                let kinds: [Any] = c.array("kinds") ?? []
                return kinds.map { GiveMessages.sourceLabel($0 as? String) }
            case "lines":
                let e = try JSONDecoder().decode(EntryExport.self, from: c.data("export"))
                return [
                    "notes": (e.fiche?.notes ?? []).map { ["line": GiveMessages.noteLine($0), "heading": GiveMessages.noteHeading($0)] },
                    "conversations": (e.conversations ?? []).map { GiveMessages.conversationLine($0) },
                ]
            case "dates":
                let dates: [Any] = c.array("dates") ?? []
                return dates.map { GiveMessages.dateLabel($0 as? String) as Any }
            case "plain":
                return GiveMessages.plainText(c.string("html") ?? "")
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }

    func testArticle() async {
        await Parity.run("carnet", "cross-household-article") { c in
            switch c.string("op") {
            case "shared":
                let a = Article.shared(try ShelfFixture.entry(c))
                var o = ReadingObs.article(a)
                o["owner"] = a.owner as Any
                o["author"] = a.author as Any
                o["summary"] = a.summary as Any
                return o
            case "text":
                ReadingFixture.session()
                return await ReadingObs.exchange {
                    let t = try await GardenAPI.articleText(slug: c.string("slug")!, locale: c.string("locale")!, owner: c.string("owner"))
                    return ["text": t.text]
                }
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }
}
