import Foundation

/// Lot C1: a tray item as the member sees it (its card) and as the server
/// gets it (its payload) — the iOS side of `ComposerSupport.kt`.
enum ComposerSupport {
    static func item(_ it: TrayItem) -> [String: Any] {
        [
            "id": it.id, "type": it.type.rawValue, "rawId": it.rawId, "title": it.title, "sub": it.sub,
            "recurse": it.recurse, "includeArchived": it.includeArchived, "exclude": it.exclude,
            "representation": it.representation, "scopeMode": it.scopeMode, "uptoChapter": it.uptoChapter,
            "selectedRefs": it.selectedRefs, "progressChapter": it.progressChapter, "progressPaused": it.progressPaused,
            "visibleChapters": it.visibleChapters,
            "includeFragments": it.includeFragments, "fragmentCount": it.fragmentCount,
            "conversationFull": it.conversationFull, "summarisable": it.summarisable, "summaryStatus": it.summaryStatus,
            "fullWeight": it.fullWeight, "uncovered": it.uncovered,
            "kind": it.kind, "na": it.na, "encrypted": it.encrypted, "moc": it.moc,
            "payload": it.payload(),
        ]
    }

    static func entry(_ e: SearchEntry) -> [String: Any] {
        ["id": e.id, "type": e.type.rawValue, "rawId": e.rawId, "title": e.title, "sub": e.sub,
         "encrypted": e.encrypted, "moc": e.moc, "kind": e.kind,
         "hasDate": e.updatedAt != nil]
    }
}
