package eu.chezmaurice.maurice.settings

import eu.chezmaurice.core.net.shownMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.Report
import eu.chezmaurice.core.settings.ReportsApi
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val PRIORITY = Color(0xFFC0392B)

/**
 * The admin's queue of open reports (`ReportsReviewView`), child safety first:
 * messages and members of shared rooms, and replies of Maurice's reported from
 * any conversation, private ones included — each with the question before it.
 * A room's report: remove the message, eject its author, or dismiss; a reply's:
 * mark it handled, or dismiss.
 */
@Composable
internal fun ReportsPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val api = remember { session.api()?.let(::ReportsApi) } ?: return
    var reports by remember { mutableStateOf<List<Report>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try { reports = api.open(); error = null } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.shownMessage() }
    }
    LaunchedEffect(Unit) { load() }

    fun act(r: Report, block: suspend () -> Unit) {
        busy = r.id
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.shownMessage() }
            load(); busy = null
        }
    }

    val list = reports
    when {
        list == null && error == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute)
        list != null && list.isEmpty() -> Text(L("reports.none"), fontSize = 14.sp, color = theme.inkMute)
    }
    error?.let { Text(it, fontSize = 12.sp, color = PRIORITY) }
    for (r in list.orEmpty()) SetCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val reason = if (r.isAiReply) "report.ai.reason.${r.reason}" else "report.reason.${r.reason}"
                Text(strings(reason).takeIf { it != reason } ?: r.reason, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                if (r.reason == "child_safety") { Spacer(Modifier.width(8.dp)); Text(L("reports.priority"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PRIORITY) }
                Spacer(Modifier.weight(1f))
                Text(r.roomTitle ?: L(if (r.isAiReply) "report.ai.queue.private" else "reports.room"), fontSize = 11.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.isAiReply) {
                Text(L("report.ai.queue.kind"), fontSize = 11.sp, color = theme.inkMute)
                r.reportedQuestion?.takeIf { it.isNotEmpty() }?.let { Text("« $it »", fontSize = 12.5.sp, color = theme.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            r.reportedMessage?.let { Text(it.content, fontSize = 13.5.sp, color = theme.ink, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            r.reportedMember?.let { Text(L("reports.member", it.displayName), fontSize = 13.5.sp, color = theme.ink) }
            r.note?.takeIf { it.isNotEmpty() }?.let { Text("« $it »", fontSize = 12.5.sp, fontStyle = FontStyle.Italic, color = theme.inkSoft) }
            r.reporterDisplayName?.let { Text(L("reports.by", it), fontSize = 11.sp, color = theme.inkMute) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val enabled = busy == null
                if (r.isAiReply) {
                    TextButton(enabled = enabled, onClick = { act(r) { api.act(r.id, removeMessage = false, ejectMember = false) } }) { Text(L("report.ai.queue.handled"), fontSize = 13.sp, color = theme.ink) }
                } else {
                    if (r.targetType == "message") TextButton(enabled = enabled, onClick = { act(r) { api.act(r.id, removeMessage = true, ejectMember = false) } }) { Text(L("reports.remove_message"), fontSize = 13.sp, color = theme.ink) }
                    TextButton(enabled = enabled, onClick = { act(r) { api.act(r.id, removeMessage = false, ejectMember = true) } }) { Text(L("reports.eject"), fontSize = 13.sp, color = PRIORITY) }
                }
                Spacer(Modifier.weight(1f))
                if (busy == r.id) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = theme.inkMute)
                TextButton(enabled = enabled, onClick = { act(r) { api.dismiss(r.id) } }) { Text(L("reports.dismiss"), fontSize = 13.sp, color = theme.inkSoft) }
            }
        }
    }
}
