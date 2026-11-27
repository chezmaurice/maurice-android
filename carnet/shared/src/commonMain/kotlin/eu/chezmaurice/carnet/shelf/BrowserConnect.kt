package eu.chezmaurice.carnet.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.core.carnet.CarnetEntry
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.OpenInvite
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.platform.plainTextClip
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The link a device code is given as: the household's landing page, the code as `XXXX-XXXX` (Carnet iOS's `Invitation.link`). */
internal fun deviceLink(serverUrl: String, code: String): String =
    Invitation(serverUrl, CarnetEntry.code(code) ?: code).link

/** « Valid for 4:32 » while the code lasts; null once it is over, or more than an hour away. */
@OptIn(ExperimentalTime::class)
internal fun remaining(iso: String, now: Instant, strings: eu.chezmaurice.ui.Strings): String? {
    val until = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val left = (until - now).inWholeSeconds
    if (left <= 0 || left >= 3_600) return null
    return strings("devices.add.countdown", "${left / 60}:${(left % 60).toString().padStart(2, '0')}")
}

/**
 * « Connect a browser » (`AddDeviceSheet(forBrowser:)`): a code that signs
 * this same member in, for the « Save to Carnet » extension in Chrome, Brave
 * or Firefox — the link to paste rather than a QR (a computer has no camera
 * pointed at the phone), the time left, a button to copy it. Watches the
 * code and says so once the browser is in.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BrowserConnectDialog(session: Session, serverUrl: String, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val api = remember(session) { session.api()?.let(::SettingsApi) }
    var code by remember { mutableStateOf<OpenInvite?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf("pending") }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        code = try { api?.ownDeviceCode() } catch (e: CancellationException) { throw e } catch (e: Throwable) { failure = e.message; null }
        val raw = code?.code ?: return@LaunchedEffect
        while (state == "pending") {
            delay(2_000)
            api?.ownDeviceCodeState(raw)?.takeIf { it != "pending" }?.let { state = it }
        }
        if (state == "used") { delay(1_500); onDismiss() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(L("browser.connect"), style = wordmark(20.sp), color = theme.ink, textAlign = TextAlign.Center)
            Text(L("browser.connect.line"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, textAlign = TextAlign.Center)
            val c = code
            when {
                failure != null -> Text(failure!!, fontSize = 14.sp, color = theme.inkSoft, textAlign = TextAlign.Center)
                state == "used" -> Text("✓  " + L("devices.add.done"), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = theme.ink,
                    modifier = Modifier.heightIn(min = 120.dp).wrapContentHeight())
                state == "expired" -> Text(L("devices.add.expired"), fontSize = 15.sp, color = theme.inkMute, textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 120.dp).wrapContentHeight())
                c != null -> {
                    val link = deviceLink(serverUrl, c.code)
                    SelectionContainer {
                        Text(link, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = theme.ink, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.inkMute.copy(alpha = 0.12f)).padding(14.dp))
                    }
                    val left by produceState<String?>(null, c.expiresAt) {
                        while (true) { value = remaining(c.expiresAt, Clock.System.now(), strings); delay(1_000) }
                    }
                    left?.let { Text(it, fontSize = 12.sp, color = theme.inkMute) }
                    GridButton(if (copied) L("browser.connect.copied") else L("browser.connect.copy"), symbol = if (copied) "checkmark" else null) {
                        scope.launch {
                            clipboard.setClipEntry(plainTextClip(link))
                            copied = true; delay(2_000); copied = false
                        }
                    }
                }
                else -> androidx.compose.material3.CircularProgressIndicator(color = theme.inkMute)
            }
            GridButton(L("common.done"), primary = true, small = true, onClick = onDismiss)
        }
    }
}
