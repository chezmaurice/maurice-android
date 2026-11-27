import XCTest

/// Carnet's ways into a household and its following one that moved (lot
/// "Carnet shelf & entries"): invitation links, typed addresses, the
/// `_maurice` TXT record, the proof, the households refreshed from /api/health.
@MainActor
final class ShelfHouseholdParity: XCTestCase {
    func testInvitation() async {
        await Parity.run("carnet", "shelf-invitation") { c in
            guard let i = Invitation(c.string) else { return NSNull() }
            // The link a parsed invitation stands for is never shown again.
            return ["server": i.server, "code": i.code]
        }
    }

    func testAddress() async {
        await Parity.run("carnet", "shelf-address") { c in
            EntryInput.normalizedAddress(c.string) as Any
        }
    }

    func testMoveRecord() async {
        await Parity.run("carnet", "shelf-move-record") { c in
            if let bytes = c.array("rdata") as? [NSNumber] {
                return ["txt": HouseholdMove.decodeTXT(Data(bytes.map { $0.uint8Value }))]
            }
            return ["moved": HouseholdMove.parse(c.string("record") ?? "") as Any]
        }
    }

    func testMoveProof() async {
        await Parity.run("carnet", "shelf-move-proof") { c in
            let proves = await HouseholdMove.proves(Parity.baseURL, key: c.string("key") ?? "")
            let requests = await ShelfObserve.requests(prefix: "/api/health")
            return ["proves": proves, "asked": requests.count]
        }
    }

    func testHouseholdRefresh() async {
        await Parity.run("carnet", "shelf-household-refresh") { c in
            let s = ShelfFixture.session()
            for _ in 0..<(c.int("times") ?? 1) { await s.refreshHouseholdIdentities() }
            let h = s.currentHousehold!
            return [
                "name": h.name, "color": h.color as Any, "icon": h.icon as Any, "key": h.key as Any,
                "serverURL": h.serverURL.replacingOccurrences(of: Parity.baseURL, with: "{base}"), "signedIn": s.hasActiveSession,
            ]
        }
    }
}
