package eu.chezmaurice.carnet.ui

import androidx.compose.runtime.Composable
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.ShelfKind
import eu.chezmaurice.ui.L

// The words Carnet builds from a count or a kind, in the member's language.

/** A kind as the shelf counts it: "Books". */
@Composable
fun kindLabel(kind: ShelfKind): String = L("carnet.kind.${kind.wire}")

/** The word a row and a page open their byline with — "Book · Ursula K. Le Guin" — in Carnet iOS's words for a kind. */
@Composable
fun kindLine(entry: ShelfEntry): String = listOfNotNull(L("suggest.kind.${entry.kind.wire}"), entry.byline).joinToString(" · ")

/** "1 note", "3 notes". */
@Composable
fun noteCount(n: Int): String = if (n == 1) L("carnet.notes.one") else L("carnet.notes.other", n)

/** "1 highlight", "3 highlights". */
@Composable
fun highlightCount(n: Int): String = if (n == 1) L("carnet.highlights.one") else L("carnet.highlights.other", n)
