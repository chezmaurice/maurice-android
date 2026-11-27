import XCTest

@MainActor
final class SessionConsentParity: XCTestCase {
    func testConsent() async {
        await Parity.run("maurice", "session-ai-consent") { c in
            let s = MauriceFixture.session()
            let store = AIConsentStore()
            let action = c.string("action")
            if action != "before" { await store.refresh(s) }
            switch action {
            case "accept": await store.decide(accept: true, session: s)
            case "decline": await store.decide(accept: false, session: s)
            case let a? where a.hasPrefix("provider:"):
                await store.accept(AIConsentStore.knownProvider(String(a.dropFirst("provider:".count))), session: s)
            default: break
            }
            return [
                // Only this store's route: a BudgetStore left by the previous class may still be reading.
                "requests": (await SessionSupport.requests()).filter { ($0["path"] as? String) == "/api/me/ai-consent" },
                "loaded": store.loaded,
                "needsAnswer": store.needsAnswer,
                "showsQuestion": store.showsQuestion,
                "declined": store.declined,
                "mayUseAI": store.mayUseAI,
                "providers": store.providers.map(\.id),
                "newProviders": store.newProviders.map(\.id),
                "record": store.record.map { ["status": $0.status, "providers": $0.providers] } as Any,
                "covers": ["ollama": store.covers("ollama"), "scaleway": store.covers("scaleway"),
                           "anthropic": store.covers("anthropic"), "mistral": store.covers("mistral")],
            ]
        }
    }
}
