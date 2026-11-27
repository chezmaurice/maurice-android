package eu.chezmaurice.maurice.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.ToolServer
import eu.chezmaurice.core.settings.ToolServersApi
import eu.chezmaurice.core.settings.toolServerAddress
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.strings
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WARM = Color(0xFFB97A1E)

/**
 * The member's own tools (`ToolsPane` on iOS): MCP servers they connect
 * themselves, by address. Only their private conversations hold them, never a
 * shared one. Adding a server asks it for its tools first: one that does not
 * answer, or refuses the token, is not kept, and its reason is shown here. A
 * server that wants a sign-in (the household answers 428) is signed in to in
 * the browser; the service brings the browser back to the household, not to
 * the app, so the pane waits, reading the list until the server appears
 * (three minutes at most; the member may cancel).
 */
@Composable
fun ToolsPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val s = strings()
    val api = remember { session.api()?.let(::ToolServersApi) } ?: return
    var servers by remember { mutableStateOf<List<ToolServer>?>(null) }
    var allowed by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    var renewing by remember { mutableStateOf<String?>(null) }
    var newToken by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }
    var removing by remember { mutableStateOf<ToolServer?>(null) }
    /** The sign-in under way in the browser, if any; it ends with the pane (the scope's). */
    var signingIn by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) { api.list()?.let { servers = it.servers; allowed = it.allowed } }

    fun show(e: Throwable) {
        val a = e as? ApiError
        error = s(if (a?.status == 422) "mytools.unreachable" else "mail.failed")
        detail = a?.message ?: e.message
    }
    fun replace(row: ToolServer) { servers = servers?.map { if (it.id == row.id) row else it } }

    /** Ask the household where to sign in, open it, and read the list until [done] says the server is there. */
    fun signIn(again: ToolServer?, start: suspend () -> String, done: (List<ToolServer>) -> Boolean) {
        error = null; detail = null; added = null
        scope.launch {
            val whereTo = tryOr(::show) { start() } ?: return@launch
            if (!(whereTo.startsWith("https://") || whereTo.startsWith("http://"))) return@launch
            runCatching { uri.openUri(whereTo) }.onFailure { show(it); return@launch }
            signingIn?.cancel()
            signingIn = scope.launch {
                repeat(90) {
                    delay(2_000)
                    val r = api.list() ?: return@repeat
                    if (done(r.servers)) {
                        servers = r.servers
                        if (again == null) {
                            added = s("mytools.added", r.servers.lastOrNull()?.tools?.size ?: 0)
                            name = ""; address = ""; token = ""
                        }
                        signingIn = null
                        return@launch
                    }
                }
                error = s("mytools.signin.timeout")
                signingIn = null
            }
        }
    }

    fun add() {
        busy = true; error = null; detail = null; added = null
        val url = toolServerAddress(address)
        val label = name.trim()
        val secret = token.trim()
        scope.launch {
            try {
                val row = api.add(label, url, secret.ifEmpty { null })
                servers = servers.orEmpty() + row
                added = s("mytools.added", row.tools.size)
                name = ""; address = ""; token = ""
                open = open + row.id
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if ((e as? ApiError)?.status == 428) {
                    // The server wants a sign-in, not a token: in the browser.
                    val known = servers.orEmpty().map { it.id }.toSet()
                    signIn(null, { api.signIn(label, url) }) { list -> list.any { it.id !in known && it.url == url } }
                } else {
                    token = ""
                    show(e)
                }
            } finally {
                busy = false
            }
        }
    }

    val canConnect = !busy && signingIn == null && name.isNotBlank() && address.isNotBlank()

    SetCaption(L("mytools.intro"))
    servers?.takeIf { it.isNotEmpty() }?.let { list ->
        SetGroup(L("mytools.yours")) {
            for (server in list) SetCard {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentGlyph("puzzlepiece.extension")
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(server.name, fontSize = 13.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(server.host, fontSize = 11.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(
                            server.enabled,
                            { on -> busy = true; error = null; detail = null; scope.launch { tryOr(::show) { api.setEnabled(server.id, on) }?.let(::replace); busy = false } },
                            enabled = !busy,
                            colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current),
                        )
                    }
                    StateLine(server)
                    if (server.state == "error") server.lastError?.let {
                        SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute) }
                    }
                    if (server.id in open) Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.surface).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        for (tool in server.tools) Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(tool.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.ink)
                            if (tool.description.isNotEmpty()) Text(tool.description, fontSize = 11.5.sp, color = theme.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (server.dropped > 0) Text(L("mytools.dropped", server.dropped), fontSize = 11.5.sp, color = WARM)
                    }
                    if (renewing == server.id) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(newToken, L("mytools.token"), secret = true, modifier = Modifier.weight(1f)) { newToken = it }
                        TextButton(enabled = !busy && newToken.isNotBlank(), onClick = {
                            busy = true; error = null; detail = null
                            scope.launch {
                                tryOr(::show) { api.setToken(server.id, newToken.trim()) }?.let { replace(it); renewing = null; newToken = "" }
                                busy = false
                            }
                        }) { Text(L("mail.save")) }
                    }
                    // Wraps on a narrow phone: five actions do not hold on one line.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("checklist", L(if (server.id in open) "mytools.hide" else "mytools.show"), enabled = server.tools.isNotEmpty()) {
                            open = if (server.id in open) open - server.id else open + server.id
                        }
                        ActionPill("arrow.clockwise", L("contacts.sync"), busy = busy) {
                            busy = true; error = null; detail = null
                            scope.launch { tryOr(::show) { api.refresh(server.id) }?.let(::replace); busy = false }
                        }
                        if (server.signsIn) ActionPill("link", L("mytools.signin.again"), enabled = !busy && signingIn == null) {
                            signIn(server, { api.signInAgain(server.id) }) { list -> list.any { it.id == server.id && it.state == "ok" } }
                        } else ActionPill("key", L("mytools.new_token"), enabled = !busy) {
                            newToken = ""; renewing = if (renewing == server.id) null else server.id
                        }
                        ActionPill("trash", L("mail.remove"), destructive = true, enabled = !busy) { removing = server }
                    }
                }
            }
        }
    }
    if (signingIn != null) SetCard {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = theme.inkMute)
            Spacer(Modifier.width(10.dp))
            Text(L("mytools.signin.waiting"), fontSize = 12.5.sp, color = theme.inkSoft, modifier = Modifier.weight(1f))
            TextButton(onClick = { signingIn?.cancel(); signingIn = null }) { Text(L("common.cancel"), fontSize = 12.sp) }
        }
    }
    if (allowed) {
        SetGroup(L("mytools.add")) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, L("mytools.name"), modifier = Modifier.fillMaxWidth()) { name = it }
                Field(address, L("mytools.address")) { address = it }
                Field(token, L("mytools.token.optional"), secret = true) { token = it }
                PrimaryButton(L(if (busy) "mytools.checking" else "mail.connect"), busy, canConnect) { add() }
                Problem(error, detail, added)
            }
        }
        SetCaption(L("mytools.privacy"))
    } else {
        // Problems of a server already there still need saying.
        Problem(error, detail, null)
        SetCaption(L("mytools.unavailable"))
    }
    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(L("mail.remove")) }, text = { Text(L("mytools.remove.confirm", server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { tryOr(::show) { api.remove(server.id) }?.let { servers = servers?.filterNot { it.id == server.id }; open = open - server.id } }
                }) { Text(L("mail.remove"), color = BAD) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }
}

/** Off, not answering, or how many tools it offers (`stateLine`). */
@Composable
private fun StateLine(server: ToolServer) = when {
    !server.enabled -> Text(L("mytools.state.off"), fontSize = 11.5.sp, color = LocalMauriceTheme.current.inkMute)
    server.state == "error" -> Text("⚠ " + L("mytools.state.error"), fontSize = 11.5.sp, color = WARM)
    else -> Text("✓ " + L("mytools.state.ok", server.tools.size), fontSize = 11.5.sp, color = GOOD)
}
