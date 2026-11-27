package eu.chezmaurice.carnet.reading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.chezmaurice.carnet.entry.handoffFailure
import eu.chezmaurice.carnet.platform.MauriceApp
import eu.chezmaurice.carnet.platform.rememberMauriceApp
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Talking with Maurice from a reader (carnet bdeda19, e3ffaec on iOS): a
 * conversation made on the server — on the chapter open, or on a passage —
 * then opened in the Maurice app. One at a time; why it failed is said.
 */
class ReaderTalk internal constructor(private val scope: CoroutineScope, val maurice: MauriceApp) {
    var opening by mutableStateOf(false); private set
    internal var failure by mutableStateOf<String?>(null)

    /** [start] makes the conversation and answers its id; [opened] is told it before the app opens it. */
    fun open(start: suspend () -> String, opened: (String) -> Unit = {}) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val id = start()
                opened(id)
                maurice.open(id)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                failure = handoffFailure(e)
            } finally { opening = false }
        }
    }
}

@Composable
fun rememberReaderTalk(): ReaderTalk {
    val scope = rememberCoroutineScope()
    val maurice = rememberMauriceApp()
    return remember(scope, maurice) { ReaderTalk(scope, maurice) }
}

/** The wait over the reader, and the failure's dialog. */
@Composable
fun ReaderTalkOverlay(talk: ReaderTalk) {
    val theme = LocalMauriceTheme.current
    if (talk.opening) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = theme.inkMute) }
    talk.failure?.let {
        AlertDialog(
            onDismissRequest = { talk.failure = null }, title = { Text(L("carnet.handoff.failed")) }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { talk.failure = null }) { Text(L("common.ok"), color = theme.ink) } },
            containerColor = theme.surface,
        )
    }
}
