package eu.chezmaurice.carnet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import eu.chezmaurice.core.session.Session

/** One page above the shelf, by its route. */
@Composable
fun RoutePage(session: Session, nav: Navigator, route: Route) {
    when (route) {
        // Named by its entry: a link that opens another entry over this one (a [[link]], a
        // `carnet://entry/…` replacing the stack) lands in the same place, and would otherwise
        // keep the open page's state — and show the old entry.
        is Route.Entry -> key(route.entry.id, route.asFiche) { eu.chezmaurice.carnet.entry.EntryScreen(session, nav, route.entry, route.asFiche) }
        Route.Notes -> eu.chezmaurice.carnet.notes.NotesScreen(nav)
        is Route.Book -> eu.chezmaurice.carnet.reading.BookScreen(nav, route.bookId, route.title, route.byline)
        is Route.Resume -> eu.chezmaurice.carnet.reading.BookResume(nav, route.bookId, route.title, route.byline)
        is Route.Reader -> eu.chezmaurice.carnet.reading.BookReader(nav, route.bookId, route.title, route.byline, route.chapters, route.index, route.summary)
        is Route.Highlights -> eu.chezmaurice.carnet.reading.HighlightsScreen(nav, route.bookId, route.title, route.byline, route.chapters)
        is Route.Read -> eu.chezmaurice.carnet.reading.ArticleReader(nav, route.slug, route.locale)
        is Route.ReadShared -> eu.chezmaurice.carnet.reading.ArticleReader(nav, route.entry.slug.orEmpty(), route.entry.locale.orEmpty(), shared = route.entry)
        Route.Maurice -> eu.chezmaurice.carnet.maurice.MauriceScreen(nav)
        is Route.Brief -> eu.chezmaurice.carnet.maurice.MauriceScreen(nav, openBrief = route.domainId)
        is Route.Conversation -> eu.chezmaurice.carnet.maurice.ConversationPage(session, nav, route.id)
    }
}
