import XCTest

@MainActor
final class DomainsEditParity: XCTestCase {
    func testDomainsEdit() async {
        await Parity.run("maurice", "domains-edit") { c in
            let store = DomainsSupport.store()
            await store.loadMaurices()   // the list as it stands: rows, then the overview
            var outcome: Any = NSNull()
            switch c.string("op") {
            case "save":
                var d = c.dict("draft") ?? [:]
                d["id"] = c.string("id") ?? "_"
                guard var draft = Maurice.parse(d) else { return ["thrown": "unparsable draft"] }
                if c.bool("new") == true { draft.rawId = nil }
                switch await store.save(draft) {
                case .saved(let m): outcome = "saved:\(m.id)"
                case .nameTaken(let n): outcome = "nameTaken:\(n)"
                case .failed: outcome = "failed"
                }
            case "setModel":
                await store.setModel(c.string("model") ?? "", for: store.maurice(for: c.string("id")))
            case "delete":
                await store.delete(c.string("id") ?? "")
                outcome = "done"
            default: break
            }
            var log = await DomainsSupport.requests()
            if c.string("op") == "save" { log = DomainsSupport.withoutBodies(log) }
            return [
                "requests": log,
                "outcome": outcome,
                "rows": store.maurices.map { ["id": $0.id, "name": $0.name, "kind": $0.kind, "model": $0.model as Any] },
                "overview": store.overview.keys.sorted(),
                "everydayModel": store.everydayModelId as Any,
            ]
        }
    }
}
