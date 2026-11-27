import XCTest

@MainActor
final class ComposerTrayParity: XCTestCase {
    func testComposerTray() async {
        await Parity.run("maurice", "composer-tray") { c in
            let store = ComposerStore(session: MauriceFixture.session())
            var results: [Any] = []
            for case let step as [String: Any] in c.array("steps") ?? [] {
                if step.keys.contains("bind") {
                    let locked = step["locked"] as? [String: Any] ?? [:]
                    await store.setConversation(step["bind"] as? String,
                                                lockedName: locked["name"] as? String ?? "",
                                                lockedCount: locked["count"] as? Int ?? 0,
                                                lockedWeight: locked["weight"] as? Int ?? 0)
                } else if let id = step["created"] as? String {
                    // A blank page's first question created it (ChatService.onCreated, wired by ChatView).
                    await store.carryTo(id)
                } else if let e = step["add"] as? [String: Any] {
                    store.add(Self.entry(e))
                } else if let e = step["toggle"] as? [String: Any] {
                    store.toggle(Self.entry(e))
                } else if let id = step["remove"] as? String {
                    store.remove(id)
                } else if let id = step["update"] as? String, var it = store.items.first(where: { $0.id == id }) {
                    let set = step["set"] as? [String: Any] ?? [:]
                    if let v = set["recurse"] as? Bool { it.recurse = v }
                    if let v = set["includeArchived"] as? Bool { it.includeArchived = v }
                    if let v = set["exclude"] as? [String] { it.exclude = v }
                    if let v = set["conversationFull"] as? Bool { it.conversationFull = v }
                    if let v = set["scopeMode"] as? String { it.scopeMode = v }
                    if let v = set["uptoChapter"] as? Int { it.uptoChapter = v }
                    if let v = set["selectedRefs"] as? [String] { it.selectedRefs = v }
                    if let v = set["representation"] as? String { it.representation = v }
                    if let v = set["includeFragments"] as? Bool { it.includeFragments = v }
                    store.update(it)
                } else if let p = step["pause"] as? [String: Any] {
                    await store.setReadingPaused(p["paused"] as? Bool ?? false, bookId: p["book"] as? String ?? "")
                } else if let book = step["reset"] as? String {
                    await store.resetReadingProgress(bookId: book)
                } else if let q = step["search"] as? String {
                    results.append((await store.search(q))?.map(ComposerSupport.entry) as Any)
                } else if let id = step["resolveNote"] as? String {
                    guard let it = store.items.first(where: { $0.id == id }) else { results.append("no such item"); continue }
                    if let r = await store.resolveNote(it) {
                        results.append(["count": r.count, "weight": r.weight, "encrypted": r.encrypted, "archivedWithheld": r.archivedWithheld,
                                        "manualExcluded": r.manualExcluded, "hasChildren": r.hasChildren, "tree": Self.note(r.tree)])
                    } else { results.append(NSNull()) }
                } else if let id = step["resolveFolder"] as? String {
                    guard let it = store.items.first(where: { $0.id == id }) else { results.append("no such item"); continue }
                    if let r = await store.resolveFolder(it) {
                        results.append(["weight": r.weight, "count": r.count, "textCount": r.textCount, "binaryCount": r.binaryCount,
                                        "hasChildren": r.hasChildren, "manualExcluded": r.manualExcluded, "tree": Self.file(r.tree)])
                    } else { results.append(NSNull()) }
                } else if let book = step["chapters"] as? String {
                    results.append(await store.bookChapters(book).map {
                        ["index": $0.index, "ref": $0.ref, "name": $0.name, "hidden": $0.hidden, "sectionType": $0.sectionType,
                         "wordCount": $0.wordCount, "hasSummary": $0.hasSummary]
                    })
                }
                await DomainsSupport.settle()
            }
            return [
                "requests": await DomainsSupport.requests(),
                "results": results,
                "items": store.items.map(ComposerSupport.item),
                "weights": Dictionary(uniqueKeysWithValues: store.items.map { ($0.id, store.weightById[$0.id] ?? 0) }),
                "counts": Dictionary(uniqueKeysWithValues: store.items.map { ($0.id, store.countById[$0.id] as Any) }),
                "heavy": Dictionary(uniqueKeysWithValues: store.items.map { ($0.id, store.heavyById[$0.id] ?? false) }),
                "total": store.total, "tier": store.tier, "over": store.over,
                "displayTotal": store.displayTotal, "attachmentCount": store.attachmentCount,
                "locked": ["name": store.lockedName, "count": store.lockedCount, "weight": store.lockedWeight],
            ]
        }
    }

    static func entry(_ d: [String: Any]) -> SearchEntry {
        SearchEntry(type: ComposerItemType(rawValue: d["type"] as? String ?? "") ?? .note, rawId: d["id"] as? String ?? "",
                    title: d["title"] as? String ?? "", sub: d["sub"] as? String ?? "",
                    encrypted: d["encrypted"] as? Bool ?? false, moc: d["moc"] as? Bool ?? false, kind: d["kind"] as? String ?? "")
    }

    static func note(_ n: ComposerStore.NoteTreeNode) -> [String: Any] {
        ["id": n.id, "title": n.title, "moc": n.moc, "archived": n.archived, "weight": n.weight, "childCount": n.childCount,
         "included": n.included, "excluded": n.excluded, "children": n.children.map(note)]
    }

    static func file(_ n: ComposerStore.FileTreeNode) -> [String: Any] {
        ["id": n.id, "name": n.name, "isFolder": n.isFolder, "kind": n.kind, "weight": n.weight, "na": n.na,
         "included": n.included, "excluded": n.excluded, "children": n.children.map(file)]
    }
}
