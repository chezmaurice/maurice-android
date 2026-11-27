import XCTest

@MainActor
final class DomainsBriefParity: XCTestCase {
    func testDomainsBrief() async {
        await Parity.run("maurice", "domains-brief") { c in
            let store = DomainsSupport.store()
            var result: [String: Any] = [:]
            switch c.string("op") {
            case "load":
                let r = await store.loadBrief("d1")
                result = ["found": r.found, "brief": DomainsSupport.brief(r.brief), "notes": r.notes.map(DomainsSupport.notes) as Any]
            case "save":
                let r = await store.saveBrief("d1", text: c.string("text") ?? "")
                result = ["ok": r.ok, "brief": DomainsSupport.brief(r.brief)]
            case "erase":
                result = ["ok": await store.deleteBrief("d1")]
            case "refresh":
                let r = await store.refreshBrief("d1")
                switch r.outcome {
                case .written(let n): result = ["outcome": "written:\(n)"]
                case .unchanged: result = ["outcome": "unchanged"]
                case .capped: result = ["outcome": "capped"]
                case .failed(let why): result = ["outcome": "failed:\(why)"]
                }
            default: break
            }
            result["requests"] = await DomainsSupport.requests { $0.hasPrefix("/api/domains/d1/brief") }
            return result
        }
    }
}
