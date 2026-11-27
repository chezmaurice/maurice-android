package eu.chezmaurice.carnet.shelf

import eu.chezmaurice.ui.accessibilityLabel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.carnet.platform.longDate
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.core.carnet.CarnetEntry
import eu.chezmaurice.core.carnet.CrossHouseholdApi
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.maurice.platform.qrMatrix
import eu.chezmaurice.maurice.platform.rememberShareText
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * « Invite a friend as a guest » (`GuestInviteSheet`): the same QR / code /
 * link screen as « Add a device », worded for someone else. The friend who
 * uses it joins this household as a guest who sees only the member, and whom
 * only the member can share with (cross-household-share.md, section A).
 * Watched every three seconds while open: it says when the friend is in.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun GuestInviteSheet(session: Session, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val language = LocalAppLanguage.current
    val share = rememberShareText()
    val api = remember { session.api()?.let(::CrossHouseholdApi) }
    val server = remember { session.state.value.current?.serverUrl.orEmpty() }
    var invite by remember { mutableStateOf<CrossHouseholdApi.GuestInvite?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf("pending") }
    LaunchedEffect(Unit) {
        try {
            invite = api?.guestInvite() ?: throw CrossHouseholdApi.Failure.SessionEnded
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            failure = when (e) {
                CrossHouseholdApi.Failure.Offline -> strings("guest.invite.offline")
                is CrossHouseholdApi.Failure.Forbidden -> strings("guest.invite.refused")
                CrossHouseholdApi.Failure.Unsupported -> strings("guest.invite.unsupported")
                is CrossHouseholdApi.Failure.Refused -> e.code
                else -> e.message.orEmpty()
            }
        }
        val code = invite?.code ?: return@LaunchedEffect
        while (state == "pending") {
            delay(3_000)
            api?.guestInviteState(code)?.takeIf { it != "pending" }?.let { state = it }
        }
    }
    // A second's tick, for the invitation that lapses while it is shown.
    val now by produceState(Clock.System.now()) { while (true) { delay(1_000); value = Clock.System.now() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(L("guest.invite"), style = wordmark(19.sp), color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
            }
            Text(L("guest.invite.sheet_title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = theme.inkSoft, textAlign = TextAlign.Center)
            val shown = invite
            val center = Modifier.heightIn(min = 240.dp).wrapContentHeight()
            when {
                state == "used" -> Text("✓  " + L("guest.invite.done"), fontSize = 19.sp, fontWeight = FontWeight.Medium, color = theme.ink, textAlign = TextAlign.Center, modifier = center)
                shown != null -> {
                    val expires = parseServerDate(shown.expiresAt)
                    if (state == "expired" || (expires != null && expires <= now)) {
                        Text(L("guest.invite.expired"), color = theme.inkSoft, textAlign = TextAlign.Center, modifier = center)
                    } else {
                        val link = shown.link?.takeIf { it.isNotEmpty() } ?: Invitation(server, CarnetEntry.code(shown.code) ?: shown.code).link
                        val matrix = remember(link) { qrMatrix(link) }
                        // Dark modules on white, whatever the theme: a scanner wants contrast.
                        if (matrix != null) Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp).accessibilityLabel(link, button = false)) {
                            Canvas(Modifier.fillMaxSize()) {
                                val cell = size.width / matrix.size
                                matrix.forEachIndexed { y, row -> row.forEachIndexed { x, dark ->
                                    if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                                } }
                            }
                        }
                        SelectionContainer {
                            Text(CarnetEntry.code(shown.code) ?: shown.code, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp, color = theme.ink)
                        }
                        validity(expires, now, language, strings)?.let { Text(it, fontSize = 12.sp, color = theme.inkSoft) }
                        GridButton(L("guest.invite.share"), symbol = "square.and.arrow.up") { share(link) }
                    }
                }
                failure != null -> Text(failure!!, color = Color(0xFFC0392B), textAlign = TextAlign.Center, modifier = center)
                else -> Box(center, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = theme.inkMute) }
            }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("person.2", L("guest.invite.only_you"))
                Hint("bubble.left.and.bubble.right", L("guest.invite.maurice"))
            }
        }
    }
}

@Composable
private fun Hint(symbol: String, text: String) {
    val theme = LocalMauriceTheme.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(DomainGlyphs.of(symbol), null, tint = theme.inkMute, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.5.sp, color = theme.inkSoft)
    }
}

/**
 * Under the code: the minutes left in its last hour (`AddDeviceSheet.remaining`),
 * else « Valid until 10 November » — a guest invitation lasts days, not minutes.
 */
@OptIn(ExperimentalTime::class)
private fun validity(expires: kotlin.time.Instant?, now: kotlin.time.Instant, language: String, strings: eu.chezmaurice.ui.Strings): String? {
    expires ?: return null
    val left = (expires - now).inWholeSeconds
    if (left in 1 until 3_600) return strings("devices.add.countdown", "${left / 60}:${(left % 60).toString().padStart(2, '0')}")
    val day = expires.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return strings("guest.invite.until", longDate(day.year, day.month.ordinal + 1, day.day, withYear = false, language = language))
}
