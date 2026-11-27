import XCTest

@MainActor
final class DomainsFormatParity: XCTestCase {
    func testDomainsFormat() async {
        let session = MauriceFixture.session()
        await Parity.run("maurice", "domains-format") { c in
            if let n = c.int("fmtTok") { return ["text": fmtTok(n)] }
            guard let p = try? JSONDecoder().decode(DomainProposal.self, from: c.data("proposal")) else { return ["text": "undecodable"] }
            return ["text": proposalNumbers(p, session: session)]
        }
    }
}
