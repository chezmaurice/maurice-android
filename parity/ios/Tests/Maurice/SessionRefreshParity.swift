import XCTest

@MainActor
final class SessionRefreshParity: XCTestCase {
    func testRefresh() async {
        await Parity.run("maurice", "session-refresh") { c in
            let s = MauriceFixture.session()
            if let before = c.dict("before"), let id = s.currentHouseholdId {
                s.updateHousehold(id: id) { h in
                    if let v = before["color"] as? String { h.color = v }
                    if let v = before["icon"] as? String { h.icon = v }
                    if let v = before["key"] as? String { h.key = v }
                }
            }
            await s.refreshFoyers()
            await SessionSupport.settle(0.3) // a 401's notice arrives on the main queue
            return [
                "household": SessionSupport.household(s),
                "households": s.households.count,
                "signedIn": s.hasActiveSession,
                "requests": await SessionSupport.distinctRequests(),
            ]
        }
    }

    func testReceive() async {
        await Parity.run("maurice", "session-receive") { c in
            let s: SessionStore
            if c.bool("paired") == true { s = MauriceFixture.session() } else { MauriceFixture.wipe(); s = SessionStore() }
            let hid = s.currentHouseholdId
            guard let invitation = Invitation(SessionSupport.sub(c.string("link") ?? "")) else { return ["invitation": NSNull()] }
            s.receive(invitation)
            // receive() refreshes the households in a task of its own: wait for it.
            await s.refreshFoyers()
            await SessionSupport.settle()
            return [
                "household": SessionSupport.household(s),
                "households": s.households.count,
                "activeMember": s.activeUserId as Any,
                "u1TokenKept": hid.map { TokenVault.get(SessionStore.tokenKey($0, "u1")) != nil } ?? false,
                "requests": await SessionSupport.distinctRequests(),
            ]
        }
    }
}
