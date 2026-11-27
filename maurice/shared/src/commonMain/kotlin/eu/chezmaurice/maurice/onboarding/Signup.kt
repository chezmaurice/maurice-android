package eu.chezmaurice.maurice.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.signup.NameCheck
import eu.chezmaurice.core.signup.SignupEnd
import eu.chezmaurice.core.signup.SignupPoll
import eu.chezmaurice.core.signup.SignupRefusal
import eu.chezmaurice.core.signup.SignupTower
import eu.chezmaurice.core.signup.followSignup
import eu.chezmaurice.maurice.consent.PRIVACY_URL
import eu.chezmaurice.maurice.describe
import eu.chezmaurice.maurice.settings.appLanguage
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TERMS_URL = "https://www.chezmaurice.eu/terms.html"

/**
 * « Créer mon Maurice » (`SignupSheet`): the form, then the wait while the
 * tower makes it, then the household joined as a scanned invitation is — its
 * first member, its admin. Opened from the welcome screen; a sign-up under
 * way (its secret kept on the device) opens straight on the wait.
 */
@Composable
fun SignupDialog(
    session: Session,
    /** An access code from a link, or typed where an invitation goes: filled in. */
    code: String = "",
    /** The address typed on the welcome screen: the one wished for. */
    name: String = "",
    onJoined: () -> Unit, onDismiss: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val language = appLanguage(session)
    val http = remember { ApiClient.defaultHttpClient() }
    val tower = remember(language) { SignupTower(http, language = language) }
    var poll by remember { mutableStateOf(SignupPoll.saved(session.store)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(theme.bg).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Text(L(if (poll == null) "common.cancel" else "common.close"), color = theme.ink)
                    }
                    Text(L("signup.title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.align(Alignment.Center))
                }
                val current = poll
                if (current != null) SignupWait(session, tower, current, onJoined, startOver = {
                    SignupPoll.save(session.store, null)
                    poll = null
                })
                else SignupForm(tower, code, name) { secret ->
                    SignupPoll.save(session.store, secret)
                    poll = secret
                }
            }
        }
    }
}

@Composable
private fun SignupForm(tower: SignupTower, initialCode: String, initialName: String, onSent: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var firstName by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(initialCode) }
    var terms by remember { mutableStateOf(false) }
    /** The address as the tower last judged it: null while unasked. */
    var check by remember { mutableStateOf<NameCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmedName = name.trim().lowercase()
    val owner = firstName.trim()
    val canSend = !busy && terms && check?.ok == true && owner.isNotEmpty() && "@" in email

    // The address, asked about as it is typed.
    LaunchedEffect(trimmedName) {
        check = null
        if (trimmedName.isEmpty()) return@LaunchedEffect
        delay(400)
        checking = true
        try { check = tower.checkName(trimmedName) } catch (e: CancellationException) { throw e } catch (_: Throwable) {} finally { checking = false }
    }
    // Addresses to choose from, from the first name.
    LaunchedEffect(owner) {
        if (owner.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(900)
        try { suggestions = tower.suggest(owner) } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
    }

    fun send() {
        if (!canSend) return
        error = null; busy = true
        scope.launch {
            try {
                onSent(tower.signup(check?.name ?: trimmedName, owner, email.trim(), code.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignupRefusal) {
                if (e.code in setOf("taken", "reserved", "shape")) check = NameCheck(ok = false, reason = e.code)
                error = e.said ?: strings("signup.error.generic")
            } catch (_: Throwable) {
                error = strings("signup.error.unreachable")
            } finally { busy = false }
        }
    }

    val caption = Modifier.padding(horizontal = 4.dp)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                firstName, { firstName = it }, singleLine = true, label = { Text(L("signup.first_name")) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
            )
            Text(L("signup.intro"), fontSize = 12.sp, color = theme.inkMute, modifier = caption)

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text(L("signup.name")) },
                placeholder = { Text(L("signup.name_placeholder")) },
                suffix = { Text(".${tower.zone}", color = theme.inkMute) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (suggestions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in suggestions) OutlinedButton(onClick = { name = s }) { Text(s, fontSize = 13.sp, color = theme.ink) }
            }
            val status = check
            when {
                checking -> Text(L("signup.name.checking"), fontSize = 12.sp, color = theme.inkMute, modifier = caption)
                status != null && trimmedName.isNotEmpty() ->
                    if (status.ok) Text("✓  " + L("signup.name.ok", "${status.name ?: trimmedName}.${tower.zone}"), fontSize = 12.sp, color = Color(0xFF2E7D32), modifier = caption)
                    else Text("✕  " + L(when (status.reason) {
                        "taken" -> "signup.name.taken"
                        "reserved" -> "signup.name.reserved"
                        else -> "signup.name.shape"
                    }), fontSize = 12.sp, color = Color(0xFFC0392B), modifier = caption)
                else -> Text(L("signup.name_caption"), fontSize = 12.sp, color = theme.inkMute, modifier = caption)
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                email, { email = it }, singleLine = true, label = { Text(L("signup.email")) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                code, { code = it }, singleLine = true, label = { Text(L("signup.code")) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(L("signup.code_caption"), fontSize = 12.sp, color = theme.inkMute, modifier = caption)

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(terms, { terms = it }, colors = CheckboxDefaults.colors(checkedColor = theme.ink, checkmarkColor = theme.bg))
                Text(markdownLinks(L("signup.terms", TERMS_URL, PRIVACY_URL), theme.ink), fontSize = 13.sp, color = theme.inkSoft)
            }
            error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFC0392B), modifier = caption) }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = ::send, enabled = canSend, modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.ink, contentColor = theme.bg),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = theme.bg, strokeWidth = 2.dp)
                else Text(L("signup.submit"), fontSize = 16.sp)
            }
        }
    }
}

/** `[label](url)` made into links, the rest as written (the terms' line). */
private fun markdownLinks(text: String, ink: Color): AnnotatedString = buildAnnotatedString {
    val link = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    var at = 0
    for (m in link.findAll(text)) {
        append(text.substring(at, m.range.first))
        withLink(LinkAnnotation.Url(m.groupValues[2], TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline)))) {
            append(m.groupValues[1])
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

/**
 * Asks the tower every few seconds how the new Maurice is doing
 * (`SignupWaitView`): the mail to confirm first, then the making, then the
 * household joined.
 */
@Composable
private fun SignupWait(session: Session, tower: SignupTower, poll: String, onJoined: () -> Unit, startOver: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    var state by remember(poll) { mutableStateOf<String?>(null) }
    /** The tower no longer knows the sign-up, or gave up on it. */
    var lost by remember(poll) { mutableStateOf(false) }
    var error by remember(poll) { mutableStateOf<String?>(null) }
    /** Made, the tower says, but its address does not answer from here yet. */
    var unanswered by remember(poll) { mutableStateOf(false) }
    LaunchedEffect(poll) {
        val end = followSignup(
            tower, poll, onState = { state = it },
            answers = { server -> try { session.health(server); true } catch (e: CancellationException) { throw e } catch (_: Throwable) { false } },
            onUnanswered = { unanswered = true },
        )
        when (end) {
            is SignupEnd.Live -> try {
                // Handed over before pairing, which replaces this screen and
                // cancels this coroutine: the member picker must have the code.
                PendingInvitation.code = end.invitation.code
                SignupPoll.save(session.store, null)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { session.receive(end.invitation) }
                onJoined()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The secret stays: closing and reopening asks again.
                error = strings.describe(e)
            }
            // Nothing more will come of this sign-up: say so, and stop asking at launch.
            SignupEnd.Lost -> { lost = true; SignupPoll.save(session.store, null) }
        }
    }
    val waiting = state == null || state == "unverified"
    val headline = when {
        lost -> "signup.wait.lost"
        unanswered -> "signup.wait.address"
        waiting -> "signup.wait.confirm"
        state == "waiting" -> "signup.wait.queue"
        else -> "signup.wait.making"
    }
    val detail = when {
        lost -> null
        unanswered -> "signup.wait.address_detail"
        waiting -> "signup.wait.confirm_detail"
        state == "waiting" -> "signup.wait.queue_detail"
        else -> "signup.wait.making_detail"
    }
    Column(
        Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
    ) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(
                DomainGlyphs.of(if (lost) "exclamationmark.triangle.fill" else if (waiting) "envelope" else "sparkles"),
                null, tint = theme.inkMute, modifier = Modifier.size(46.dp),
            )
            Text(L(headline), style = wordmark(21.sp), color = theme.ink, textAlign = TextAlign.Center)
            detail?.let { Text(L(it), fontSize = 15.sp, color = theme.inkMute, textAlign = TextAlign.Center) }
            error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFC0392B), textAlign = TextAlign.Center) }
            if (!lost && error == null) CircularProgressIndicator(Modifier.size(20.dp), color = theme.inkMute, strokeWidth = 2.dp)
        }
        TextButton(onClick = startOver) {
            Text(L("signup.start_over"), fontSize = 13.sp, color = if (lost) theme.ink else Color(0xFFC0392B))
        }
    }
}
