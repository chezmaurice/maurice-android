package eu.chezmaurice.carnet.entry

import eu.chezmaurice.ui.L
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.Route
import eu.chezmaurice.carnet.platform.rememberMauriceApp
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.core.carnet.EntryConversation
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.launch

/** Why Maurice could not be asked, as MauriceAPI.message says it on iOS: the server's words, else "HTTP <status>". */
fun handoffFailure(e: Throwable): String? =
    (e as? eu.chezmaurice.core.net.ApiError)?.let { if (it.fromServer || it.status == 0) it.message else "HTTP ${it.status}" } ?: e.message

/**
 * "Talk with Maurice" about an entry (`mauriceHandoff`): in the Maurice
 * app with the entry in context — a new conversation, or one already held
 * about it — or, without the app, in Carnet's own thread. The dialogs it
 * needs are drawn where it is called.
 */
@Composable
fun rememberAsk(nav: Navigator, entry: ShelfEntry?): () -> Unit {
    val theme = LocalMauriceTheme.current
    val api = LocalApi.current
    val garden = remember(api) { api?.let { GardenApi(it) } }
    val scope = rememberCoroutineScope()
    val maurice = rememberMauriceApp()
    var choosing by remember { mutableStateOf<List<EntryConversation>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun start(e: ShelfEntry) = scope.launch {
        runCatching { garden!!.startConversation(e.id) }
            .onSuccess { id -> if (maurice.installed) maurice.open(id) else nav.push(Route.Conversation(id)) }
            .onFailure { failure = handoffFailure(it) }
    }

    choosing?.let { past ->
        val e = entry
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(L("carnet.handoff.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (maurice.installed) L("carnet.handoff.in_app") else L("carnet.handoff.in_context"), fontSize = 13.sp, color = theme.inkSoft)
                    TextButton(onClick = { choosing = null; if (e != null) start(e) }) { Text(L("carnet.handoff.new"), color = theme.ink) }
                    for (c in past.take(6)) TextButton(onClick = {
                        choosing = null
                        if (maurice.installed) maurice.open(c.id) else nav.push(Route.Conversation(c.id))
                    }) { Text("${c.displayTitle} · ${whenOf(c.updatedAt)}", color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text(L("common.cancel"), color = theme.inkSoft) } },
            containerColor = theme.surface,
        )
    }
    failure?.let {
        AlertDialog(
            onDismissRequest = { failure = null }, title = { Text(L("carnet.handoff.failed")) }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(L("common.ok"), color = theme.ink) } }, containerColor = theme.surface,
        )
    }

    return {
        val e = entry
        if (e != null && garden != null) scope.launch {
            val past = runCatching { garden.conversations(e) }.getOrDefault(emptyList())
            if (past.isEmpty()) start(e) else choosing = past
        }
    }
}
