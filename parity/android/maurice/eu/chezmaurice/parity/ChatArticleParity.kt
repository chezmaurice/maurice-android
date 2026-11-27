package eu.chezmaurice.parity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test

/** A page shared from another app, read into an article card (chat-article.json). */
class ChatArticleParity {
    @Test fun chatArticle() = Parity.check("maurice", "chat-article") { c ->
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        try {
            val chat = ChatSupport.controller(scope)
            val a = chat.readArticle(c.string("url").orEmpty(), c.string("title"))
            mapOf(
                "sent" to ChatSupport.sent(ChatSupport.log()),
                "card" to mapOf(
                    "kind" to "article", "title" to a.title, "site" to a.site, "author" to a.author, "date" to a.date,
                    "url" to a.url, "text" to a.text, "source" to a.source, "wordCount" to a.wordCount, "fenced" to a.fenced,
                ),
            )
        } finally {
            scope.cancel()
        }
    }
}
