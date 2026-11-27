import XCTest

@MainActor
final class SessionInvitationParity: XCTestCase {
    func testInvitation() async {
        await Parity.run("maurice", "session-invitation") { c in
            guard let i = Invitation(c.string) else { return nil }
            return ["server": i.server, "code": i.code, "link": i.link]
        }
    }

    func testInvitationLink() async {
        await Parity.run("maurice", "session-invitation-link") { c in
            Invitation(server: c.string("server") ?? "", code: c.string("code") ?? "").link
        }
    }

    func testAddress() async {
        await Parity.run("maurice", "session-address") { c in
            PairingView.normalizedURL(c.string)
        }
    }
}
