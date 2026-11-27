import XCTest

/// A person fiche reviewed in the app (parity/vectors/maurice/content-people.json).
@MainActor
final class ContentPeopleParity: XCTestCase {
    func testContentPeople() async {
        await Parity.run("maurice", "content-people") { c in
            switch c.string("op") {
            case "ref":
                guard let url = URL(string: c.string("url") ?? ""), let r = PersonRef(gardenURL: url) else { return NSNull() }
                return ["locale": r.locale, "basename": r.basename, "anchor": r.anchor as Any, "id": r.id]
            case "sources":
                return ["text": withoutSources(c.string("text") ?? "")]
            case "model":
                guard let m = try? JSONDecoder().decode(PersonReviewModel.self, from: c.data("json")) else { return "undecodable" }
                return Self.model(m)
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }

    static func model(_ m: PersonReviewModel) -> [String: Any] {
        [
            "title": m.title, "locale": m.locale, "basename": m.basename, "status": m.status, "byMaurice": m.byMaurice,
            "relation": [
                "text": m.relation.text as Any, "status": m.relation.status as Any, "since": m.relation.since as Any,
                "until": m.relation.until as Any, "edited": m.relation.edited as Any,
                "proposed": m.relation.proposed.map { ["text": $0.text, "since": $0.since as Any, "until": $0.until as Any] } as Any,
            ],
            "identities": m.identities.map {
                ["address": $0.address, "mailboxes": $0.mailboxes, "status": $0.status, "source": $0.source, "conflict": $0.conflict as Any]
            },
            "fragments": m.fragments.map {
                ["id": $0.id, "summary": $0.summary, "status": $0.status, "origin": $0.origin as Any, "address": $0.address as Any,
                 "mailbox": $0.mailbox as Any, "body": $0.body, "edited": $0.edited as Any]
            },
            "exchanges": m.exchanges as Any, "pending": m.pending,
        ]
    }
}
