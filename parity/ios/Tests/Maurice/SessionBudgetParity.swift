import XCTest

@MainActor
final class SessionBudgetParity: XCTestCase {
    func testBudget() async {
        await Parity.run("maurice", "session-budget") { c in
            let s = MauriceFixture.session()
            let store = BudgetStore()
            var seen: [[String: Any]] = []
            for step in c.array("steps") ?? [] {
                await Parity.reset([SessionBudgetParity.route(step)])
                await store.refresh(s)
                seen.append(["left": store.left as Any, "isLimited": store.isLimited, "alert": store.alert as Any])
            }
            return seen
        }
    }

    /// One step: a budget as JSON, `{"status": n}` a failure, `{"text": …}` a raw body.
    nonisolated static func route(_ step: Any) -> [String: Any] {
        let d = step as? [String: Any] ?? [:]
        var r: [String: Any] = ["method": "GET", "path": "/api/me/budget"]
        if let status = d["status"] { r["status"] = status; r["json"] = ["error": "x"] }
        else if let text = d["text"] { r["text"] = text }
        else { r["json"] = d }
        return r
    }
}
