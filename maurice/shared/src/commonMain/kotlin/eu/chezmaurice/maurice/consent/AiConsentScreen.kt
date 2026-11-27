package eu.chezmaurice.maurice.consent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.consent.AiConsentStore
import eu.chezmaurice.core.consent.AiProvider
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.platform.countryName
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.MauriceIcons
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch

/** The signed-in member's answer about AI providers; null outside the home screen. */
val LocalAiConsent = staticCompositionLocalOf<AiConsentStore?> { null }

const val PRIVACY_URL = "https://www.chezmaurice.eu/privacy.html"

/**
 * The household's providers in one phrase — "Scaleway Generative APIs
 * (Paris, France)" — for the screens that send a kind of data (mail,
 * imports): `AIConsentStore.providerNames`.
 */
fun providerNames(providers: List<AiProvider>, language: String): String =
    providers.joinToString(", ") { p -> p.place(language)?.let { "${p.name} ($it)" } ?: p.name }

/** Whether the member's yes lets their data go to the household's providers; true outside the home screen. */
@Composable
fun rememberMayUseAI(): Boolean {
    val consent = LocalAiConsent.current ?: return true
    val state by consent.state.collectAsState()
    return state.mayUseAI
}

/**
 * Who reads a kind of data, said where it is sent (mail reading, imports):
 * the providers named, or the household's own machine; and, without a yes
 * to them yet, where to give it. [needsYes]: whether the action waits on it.
 */
@Composable
fun SentToLine(localKey: String, namedKey: String, needsYes: Boolean = true) {
    val consent = LocalAiConsent.current ?: return
    val state by consent.state.collectAsState()
    val theme = LocalMauriceTheme.current
    Text(
        if (state.providers.isEmpty()) L(localKey) else L(namedKey, providerNames(state.providers, LocalAppLanguage.current)),
        fontSize = 11.5.sp, color = theme.inkSoft,
    )
    if (needsYes && !state.mayUseAI) Text(L("ai.consent.needed"), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
}

/** The language the app is shown in, for names the strings tables do not hold (countries). */
val LocalAppLanguage = staticCompositionLocalOf { "en" }

/** "Paris, France" in the app's language, or null when unknown. */
fun AiProvider.place(language: String): String? {
    val country = region?.let { countryName(it, language) }
    return listOfNotNull(city, country).joinToString(", ").ifEmpty { null }
}

/**
 * The question asked before anything a member writes reaches an AI
 * provider, as `AIConsentView.swift`: which providers, where, what is sent,
 * and that Maurice is an AI whose answers can be wrong. Shown after sign-in,
 * for every role, until the member answers, and again when a provider
 * appears that their yes does not cover. It cannot be dismissed: the answer
 * is yes or no.
 */
@Composable
fun AiConsentScreen(store: AiConsentStore, providers: List<AiProvider>, newProviders: List<AiProvider>, renewing: Boolean) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun decide(accept: Boolean) {
        busy = true
        scope.launch { store.decide(accept); busy = false }
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(theme.bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BoaterHat(size = 34.dp, color = theme.ink)
                        Text(L("ai.consent.title"), style = wordmark(26.sp), color = theme.ink)
                        if (renewing && newProviders.isNotEmpty()) {
                            Text(L("ai.consent.new_provider"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = legible(accent, theme.isDark))
                        }
                        Text(L("ai.consent.intro"), fontSize = 15.sp, color = theme.inkSoft, lineHeight = 21.sp)
                    }
                    AiProviderList(providers, highlighted = newProviders.map { it.id }.toSet())
                    AiDataList()
                    Notes()
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { decide(true) }, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                    ) { Text(L("ai.consent.accept"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp)) }
                    OutlinedButton(
                        onClick = { decide(false) }, enabled = !busy,
                        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                    ) { Text(L("ai.consent.decline"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Notes() {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DomainGlyphs.of("exclamationmark.triangle.fill"), null, tint = theme.inkSoft, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(8.dp))
            Text(L("ai.consent.accuracy"), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
        }
        Text(L("ai.consent.local"), fontSize = 12.5.sp, color = theme.inkMute)
        Text(L("ai.consent.footer"), fontSize = 12.5.sp, color = theme.inkMute)
        Text(
            L("ai.consent.privacy"), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = legible(LocalAccent.current, theme.isDark),
            modifier = Modifier.clickable { uri.openUri(PRIVACY_URL) },
        )
    }
}

/** The providers, each with where it processes. Shared by the consent screen and Settings → My data. */
@Composable
fun AiProviderList(providers: List<AiProvider>, highlighted: Set<String> = emptySet()) {
    val theme = LocalMauriceTheme.current
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(L("ai.consent.providers.header"))
        if (providers.isEmpty()) Text(L("ai.consent.providers.none"), fontSize = 14.sp, color = theme.inkSoft)
        for (p in providers) {
            Row(
                Modifier.fillMaxWidth().clip(shape).background(theme.surface).border(0.5.dp, theme.rule, shape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(DomainGlyphs.of("server.rack"), null, tint = theme.inkSoft, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                    p.place(LocalAppLanguage.current)?.let { Text(it, fontSize = 12.5.sp, color = theme.inkMute) }
                }
                if (p.id in highlighted) {
                    Text(
                        L("ai.consent.new"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.ink,
                        modifier = Modifier.clip(CircleShape).background(theme.codeBg).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** What goes to the providers. */
@Composable
fun AiDataList() {
    val theme = LocalMauriceTheme.current
    val rows: List<Pair<ImageVector, String>> = listOf(
        DomainGlyphs.of("bubble.left") to "ai.consent.data.messages",
        DomainGlyphs.of("paperclip") to "ai.consent.data.attachments",
        DomainGlyphs.of("books.vertical") to "ai.consent.data.context",
        DomainGlyphs.of("envelope") to "ai.consent.data.mail",
        DomainGlyphs.of("tray.full") to "ai.consent.data.imports",
        DomainGlyphs.of("moon.stars") to "ai.consent.data.night",
        MauriceIcons.Mic to "ai.consent.data.dictation",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(L("ai.consent.data.header"))
        for ((icon, key) in rows) Row {
            Icon(icon, null, tint = theme.inkSoft, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            Spacer(Modifier.size(12.dp))
            Text(L(key), fontSize = 14.sp, color = theme.ink)
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = LocalMauriceTheme.current.inkMute)

/** Above the composer while the member's answer is no: nothing is sent, and the way back is one tap away. */
@Composable
fun AiDeclinedBanner(onReview: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(theme.surface).border(0.5.dp, theme.rule, shape).padding(12.dp)) {
        Icon(DomainGlyphs.of("hand.raised"), null, tint = theme.inkSoft, modifier = Modifier.size(15.dp))
        Spacer(Modifier.size(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("ai.consent.declined.banner"), fontSize = 13.sp, color = theme.ink)
            Text(
                L("ai.consent.declined.review"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = legible(LocalAccent.current, theme.isDark),
                modifier = Modifier.clickable(onClick = onReview),
            )
        }
    }
}

/** The domain editor's question, when a model is chosen whose provider the member has not accepted yet. */
@Composable
fun AiProviderAskDialog(store: AiConsentStore, provider: AiProvider, onDone: (Boolean) -> Unit) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onDone(false) }) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.widthIn(max = 440.dp).clip(shape).background(theme.bg).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(L("ai.consent.ask.title"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
            AiProviderList(listOf(provider))
            Text(L("ai.consent.ask.text", provider.name), fontSize = 14.sp, color = theme.inkSoft)
            Text(L("ai.consent.accuracy"), fontSize = 12.5.sp, color = theme.inkMute)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onDone(false) }) { Text(L("ai.consent.ask.cancel"), color = theme.ink) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            store.accept(provider.id)
                            busy = false
                            onDone(store.state.value.covers(provider.id))
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.White),
                ) { Text(L("ai.consent.accept")) }
            }
        }
    }
}
