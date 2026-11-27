import XCTest

@MainActor
final class SessionLeaveParity: XCTestCase {
    func testLeave() async {
        await Parity.run("maurice", "session-leave") { c in
            let s = MauriceFixture.session()
            let hid = s.currentHouseholdId!
            if c.bool("second") == true {
                // u2 signed in on this device earlier, then u1 again.
                s.updateDeviceUsers(s.deviceUsers + [DeviceUser(id: "u2", displayName: "Bob", avatarColor: "#2c5aa0",
                                                                  hasPin: true, role: "standard")])
                s.loginUser("u2", token: "tok-u2")
                s.loginUser("u1", token: "tok-u1")
            }
            switch c.string("action") {
            case "signOut": s.signOut()
            case "forget": s.unpair()
            default: break
            }
            await SessionSupport.settle()
            return [
                "requests": await SessionSupport.requests(sorted: true),
                "households": s.households.count,
                "activeMember": s.activeUserId as Any,
                "u1TokenKept": TokenVault.get(SessionStore.tokenKey(hid, "u1")) != nil,
                "u2TokenKept": TokenVault.get(SessionStore.tokenKey(hid, "u2")) != nil,
            ]
        }
    }

    func testRejected() async {
        await Parity.run("maurice", "session-rejected") { c in
            let s = MauriceFixture.session()
            let hid = s.currentHouseholdId!
            switch c.string("via") {
            case "prefs": await s.loadPreferences()
            case "budget": await BudgetStore().refresh(s)
            case "consent": await AIConsentStore().refresh(s)
            default: break
            }
            await SessionSupport.settle(0.3) // the notice is delivered on the main queue
            return [
                "signedIn": s.hasActiveSession,
                "activeMember": s.activeUserId as Any,
                "sessionEnded": s.sessionEnded,
                "u1TokenKept": TokenVault.get(SessionStore.tokenKey(hid, "u1")) != nil,
                "households": s.households.count,
            ]
        }
    }
}
