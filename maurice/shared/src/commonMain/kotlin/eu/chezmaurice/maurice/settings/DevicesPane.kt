package eu.chezmaurice.maurice.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.OwnSession
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.budget.longDate
import eu.chezmaurice.maurice.platform.relativeWithinWeek
import eu.chezmaurice.ui.L
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.maurice.chat.legible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "My devices" (`DevicesView`, 26 November 2026): the devices signed in as
 * this member, and the way to sign one out from another — a phone lost or
 * sold. A device signed out here is answered 401 at its next request and goes
 * back to "Who's here?" (the session ended, said in grey).
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
internal fun DevicesPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    val scope = rememberCoroutineScope()
    val language = appLanguage(session)
    var sessions by remember { mutableStateOf<List<OwnSession>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<OwnSession?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }
    var closingOthers by remember { mutableStateOf(false) }
    var othersClosed by remember { mutableStateOf(false) }

    suspend fun load() {
        try { sessions = api.ownSessions(); failed = false }
        catch (e: CancellationException) { throw e }
        catch (_: Throwable) { failed = true }
    }
    LaunchedEffect(Unit) { load() }

    /** "Last used 3 days ago" — or, for a session never used since it opened, the day it signed in. */
    fun usage(s: OwnSession, strings: (String, String) -> String): String {
        fun whenOf(raw: String): String {
            val at = parseServerDate(raw)?.toEpochMilliseconds() ?: return raw
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            return (if (now - at < 7 * 86_400_000L) relativeWithinWeek(at, now) else null) ?: longDate(raw, language) ?: raw
        }
        return s.lastUsedAt?.let { strings("mydevices.last_used", whenOf(it)) } ?: strings("mydevices.signed_in", whenOf(s.createdAt))
    }

    Text(L("mydevices.caption"), fontSize = 12.5.sp, color = theme.inkSoft, lineHeight = 17.sp)

    val list = sessions
    if (list != null) {
        val lastUsed = L("mydevices.last_used", "%1")
        val signedIn = L("mydevices.signed_in", "%1")
        val words: (String, String) -> String = { key, arg -> (if (key == "mydevices.last_used") lastUsed else signedIn).replace("%1", arg) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(12.dp)),
        ) {
            list.forEachIndexed { index, s ->
                if (index > 0) SetDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
                    AccentGlyph(s.icon)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(s.family ?: L("mydevices.unknown"), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                            if (s.current) Text(
                                L("mydevices.this"), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = legible(accent, theme.isDark),
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        s.detail?.let { Text(it, fontSize = 11.5.sp, color = theme.inkSoft) }
                        Text(usage(s, words), fontSize = 11.5.sp, color = theme.inkMute)
                    }
                    if (!s.current) {
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = busy == null && !closingOthers) { closing = s }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (busy == s.id) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = BAD); Spacer(Modifier.width(5.dp)) }
                            Text(L("mydevices.disconnect"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = BAD.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
        if (list.any { !it.current }) {
            ActionPill("rectangle.portrait.and.arrow.right", L("mydevices.close_others"), busy = closingOthers, destructive = true) { confirmOthers = true }
        } else {
            StatusLine(L(if (othersClosed) "mydevices.others_closed" else "mydevices.only_this"), ok = othersClosed)
        }
    } else if (!failed) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute)
    }
    if (failed) StatusLine(L("mydevices.failed"), bad = true)

    closing?.let { s ->
        AlertDialog(
            onDismissRequest = { closing = null },
            containerColor = theme.surface,
            title = { Text(L("mydevices.disconnect.confirm"), color = theme.ink) },
            text = { Text(L("mydevices.disconnect.body"), color = theme.inkSoft) },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    busy = s.id
                    scope.launch {
                        // Already closed from elsewhere (404): the list says so once read again.
                        val ok = runCatching { api.closeSession(s.id) }.onFailure { if (it is CancellationException) throw it }.isSuccess
                        if (ok) load() else failed = true
                        busy = null
                    }
                }) { Text(L("mydevices.disconnect"), color = BAD) }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(L("common.cancel"), color = theme.ink) } },
        )
    }
    if (confirmOthers) {
        AlertDialog(
            onDismissRequest = { confirmOthers = false },
            containerColor = theme.surface,
            title = { Text(L("mydevices.close_others.confirm"), color = theme.ink) },
            text = { Text(L("mydevices.close_others.body"), color = theme.inkSoft) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOthers = false
                    closingOthers = true
                    scope.launch {
                        val ok = runCatching { api.closeOtherSessions() }.onFailure { if (it is CancellationException) throw it }.isSuccess
                        if (ok) { othersClosed = true; load() } else failed = true
                        closingOthers = false
                    }
                }) { Text(L("mydevices.close_others"), color = BAD) }
            },
            dismissButton = { TextButton(onClick = { confirmOthers = false }) { Text(L("common.cancel"), color = theme.ink) } },
        )
    }
}
