import XCTest

@MainActor
final class DomainsProposalsParity: XCTestCase {
    func testDomainsProposals() async {
        await Parity.run("maurice", "domains-proposals") { c in
            let chat = ChatService(session: MauriceFixture.session())
            await chat.loadProposals()
            var result: Any = NSNull()
            switch c.string("op") {
            case "seen":
                await chat.markProposalsSeen()
            case "apply":
                let items = (c.array("items") as? [[String: Any]] ?? []).map {
                    DomainProposalApplyItem(id: $0["id"] as? String ?? "", action: $0["action"] as? String ?? "",
                                            name: $0["name"] as? String, summary: $0["summary"] as? String,
                                            seed: $0["seed"] as? Bool ?? false)
                }
                if let r = await chat.applyProposals(items) {
                    result = ["adopted": r.adopted.map { ["id": $0.id, "domainId": $0.domain_id] },
                              "errors": r.errors.map { ["id": $0.id, "error": $0.error] }]
                }
            case "restore":
                result = await chat.restoreProposal(c.string("id") ?? "")
            case "merge":
                result = await chat.mergeProposals(c.array("ids") as? [String] ?? [], name: c.string("name"))
            case "split":
                result = await chat.splitProposal(c.string("id") ?? "", part: DomainProposalSplitPart(
                    name: c.string("name") ?? "", conversation_ids: c.array("conversations") as? [String] ?? [],
                    mail: c.array("mail") as? [String] ?? []))
            case "detail":
                if let d = await chat.proposalDetail(c.string("id") ?? "") {
                    result = ["id": d.id, "name": d.name, "summary": d.summary, "state": d.state,
                              "conversations": (d.conversations_list ?? []).map { ["id": $0.id, "date": $0.date as Any, "title": $0.title as Any] },
                              "mail": (d.mail_list ?? []).map { ["path": $0.path, "from": $0.from as Any, "to": $0.to as Any, "title": $0.title as Any] }]
                }
            default: break
            }
            return [
                "requests": await DomainsSupport.requests { $0.hasPrefix("/api/domains/proposals") },
                "result": result,
                "open": chat.openProposals.map(DomainsSupport.proposal),
                "settled": chat.settledProposals.map(\.id),
                "restorable": chat.settledProposals.filter(\.canRestore).map(\.id),
                "unseen": chat.proposalsUnseen,
            ]
        }
    }
}
