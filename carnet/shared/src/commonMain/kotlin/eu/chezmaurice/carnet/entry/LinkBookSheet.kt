package eu.chezmaurice.carnet.entry

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.ui.BookCover
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.folded
import eu.chezmaurice.carnet.ui.matchesAll
import eu.chezmaurice.carnet.ui.minePaper
import eu.chezmaurice.core.carnet.Book
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.EntryPage
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Tying a book entry to a book of the household's library, by hand
// (`LinkBookSheet`): what the title alone could not settle — another
// edition, a subtitle, an author the library does not know — or settled
// wrongly. The fiche carries the book's id from then on, and the entry
// reads, highlights and resumes from that book.

/** The title without its subtitle: the library often has one without the other. */
internal fun linkBookQuery(title: String): String = title.split(':', '—', '–', '(').first().trim()

/** Every word typed, in the title or the authors, accents and case aside. */
internal fun linkBookMatches(books: List<Book>, query: String): List<Book> =
    books.filter { matchesAll(query, it.title + " " + it.authors.joinToString(" ")) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkBookSheet(calibre: CalibreApi, garden: GardenApi, entry: ShelfEntry, onLinked: (EntryPage) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var query by remember { mutableStateOf(linkBookQuery(entry.title)) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf<Int?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val current = if (entry.source?.isBook == true) entry.source?.bookId else null
    val found = linkBookMatches(books, query)

    LaunchedEffect(Unit) {
        try { books = calibre.books().sortedBy { it.title.folded() } }
        catch (e: CancellationException) { throw e } catch (_: Throwable) { loadError = strings("link_book.offline") }
        loading = false
    }
    fun link(book: Book) {
        linking = book.id; linkError = null
        scope.launch {
            try { onLinked(garden.linkBook(entry, book.id)); onDismiss() }
            catch (e: CancellationException) { throw e } catch (e: Throwable) { linkError = e.message }
            linking = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L(if (current == null) "link_book.title" else "link_book.title_change"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.close"), color = theme.ink) }
            }
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(L("link_book.hint"), fontSize = 13.5.sp, color = theme.inkSoft)
                val style = TextStyle(fontSize = 15.sp, color = theme.ink)
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = style, cursorBrush = SolidColor(theme.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().minePaper(theme, 14.dp).border(0.5.dp, theme.ruleHard, RoundedCornerShape(14.dp)).padding(12.dp),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text(L("link_book.search"), style = style.copy(color = theme.inkMute)); inner() } },
                )
                linkError?.let { Text(it, fontSize = 13.sp, color = theme.inkSoft) }
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute)
                }
                loadError != null -> Note(loadError!!)
                found.isEmpty() -> Note(L(if (books.isEmpty()) "link_book.empty_library" else "link_book.none"))
                else -> LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    items(found.take(60), key = { it.id }) { book ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = linking == null && book.id != current) { link(book) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BookCover(book.id, book.coverLetter, 34.dp, 48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(book.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.author, fontSize = 12.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            when {
                                linking == book.id -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute)
                                book.id == current -> Icon(DomainGlyphs.of("checkmark"), L("link_book.current"), tint = theme.ink, modifier = Modifier.size(14.dp))
                            }
                        }
                        GridRule()
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.5.sp, color = LocalMauriceTheme.current.inkSoft, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 40.dp))
}
