package eu.chezmaurice.parity

import eu.chezmaurice.core.composer.SearchEntry
import eu.chezmaurice.core.composer.TrayItem

/** Lot C1: a tray item as the member sees it and as the server gets it — the Android side of `ComposerSupport.swift`. */
object ComposerSupport {
    fun item(it: TrayItem): Map<String, Any?> = mapOf(
        "id" to it.id, "type" to it.type.wire, "rawId" to it.rawId, "title" to it.title, "sub" to it.sub,
        "recurse" to it.recurse, "includeArchived" to it.includeArchived, "exclude" to it.exclude,
        "representation" to it.representation, "scopeMode" to it.scopeMode, "uptoChapter" to it.uptoChapter,
        "selectedRefs" to it.selectedRefs, "progressChapter" to it.progressChapter, "progressPaused" to it.progressPaused,
        "visibleChapters" to it.visibleChapters,
        "includeFragments" to it.includeFragments, "fragmentCount" to it.fragmentCount,
        "conversationFull" to it.conversationFull, "summarisable" to it.summarisable, "summaryStatus" to it.summaryStatus,
        "fullWeight" to it.fullWeight, "uncovered" to it.uncovered,
        "kind" to it.kind, "na" to it.na, "encrypted" to it.encrypted, "moc" to it.moc,
        "payload" to it.payload(),
    )

    fun entry(e: SearchEntry): Map<String, Any?> = mapOf(
        "id" to e.id, "type" to e.type.wire, "rawId" to e.rawId, "title" to e.title, "sub" to e.sub,
        "encrypted" to e.encrypted, "moc" to e.moc, "kind" to e.kind,
        "hasDate" to (e.updatedAt != null),
    )
}
