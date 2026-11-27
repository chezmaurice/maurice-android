import XCTest

/// Settings › Subscription (lot Settings): the catalogue, the tower's account
/// state, StoreStore.refresh. In the simulator the App Store sells nothing
/// (no StoreKit configuration): products and purchases stay out of reach.
@MainActor
final class SettingsStoreParity: XCTestCase {
    func testSettingsStore() async {
        await Parity.run("maurice", "settings-store") { c in
            switch c.string("kind") {
            case "catalogue":
                return ["subscriptions": StoreStore.subscriptions, "topUps": StoreStore.topUps]
            case "state":
                guard let s = SettingsSupport.decode(StoreAccountState.self, c) else { return "undecodable" }
                return Self.state(s)
            case "refresh":
                await Self.serve(c)
                let store = StoreStore()
                await store.refresh(MauriceFixture.session(role: c.string("role")))
                return [
                    "requests": await Parity.requests(),
                    "hasStore": store.hasStore,
                    "account": store.account?.account as Any,
                    "state": store.state.map(Self.state) as Any,
                    "error": store.error as Any,
                ]
            default:
                return ["unknown kind": c.string("kind") as Any]
            }
        }
    }

    static func state(_ s: StoreAccountState) -> [String: Any?] {
        ["name": s.name, "server": s.server, "state": s.state, "via": s.via, "plan": s.plan, "products": s.products,
         "isSubscribed": s.isSubscribed, "isInTrial": s.isInTrial]
    }

    /// The case's `serve` routes, `{{base}}` made the fake household's address.
    static func serve(_ c: ParityCase) async {
        let data = (try? JSONSerialization.data(withJSONObject: c["serve"] ?? [])) ?? Data("[]".utf8)
        let text = String(decoding: data, as: UTF8.self)
            .replacingOccurrences(of: "{{base}}", with: Parity.baseURL)
        let routes = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) ?? []
        await Parity.reset(routes)
    }
}
