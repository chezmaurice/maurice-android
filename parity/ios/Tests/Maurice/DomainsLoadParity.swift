import XCTest

@MainActor
final class DomainsLoadParity: XCTestCase {
    func testDomainsLoad() async {
        await Parity.run("maurice", "domains-load") { _ in
            let store = DomainsSupport.store()
            await store.load()
            var resolved: [String: Any] = ["__everyday__": store.resolvedModel(for: .everyday)?.name as Any]
            for m in store.maurices { resolved[m.id] = store.resolvedModel(for: m)?.name as Any }
            // The proposals are ChatService's on iOS (DomainsProposalsParity): left out here.
            let requests = await DomainsSupport.requests { !$0.hasPrefix("/api/domains/proposals") }
            return [
                "requests": requests,
                "rows": store.maurices.map(DomainsSupport.row),
                "domains": store.domains.map(\.id),
                "companions": store.companions.map(\.id),
                "overview": store.overview.mapValues(DomainsSupport.overview),
                "models": store.models.map(DomainsSupport.model),
                "defaultModel": store.defaultModelId as Any,
                "everydayModel": store.everydayModelId as Any,
                "families": store.families.map(DomainsSupport.family),
                "resolvedModel": resolved,
            ]
        }
    }
}
