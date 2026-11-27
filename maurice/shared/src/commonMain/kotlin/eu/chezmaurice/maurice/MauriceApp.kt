package eu.chezmaurice.maurice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.chezmaurice.maurice.onboarding.ConnectDisclosure
import eu.chezmaurice.maurice.chat.OpenRequest
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.chat.HomeScreen
import eu.chezmaurice.maurice.onboarding.MemberPickerScreen
import eu.chezmaurice.maurice.onboarding.PairingScreen
import eu.chezmaurice.maurice.resources.Res
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.MauriceThemeProvider
import eu.chezmaurice.ui.Strings

private val LANGUAGES = setOf("de", "en", "es", "fr", "it", "nl", "pt")

/**
 * The app's words in [locale] (null: the device's): the iOS table, then the
 * Android-only one over it, English underneath both. Carnet borrows them for
 * the screens it shares with Maurice (pairing, the member picker).
 */
suspend fun loadMauriceStrings(locale: String?): Strings {
    val language = appLanguage(locale)
    val english = mauriceTable("en")
    return Strings(if (language == "en") english else mauriceTable(language), english)
}

/** The language the apps speak: [locale] if they hold it, else the device's, else English. */
fun appLanguage(locale: String?): String =
    locale?.takeIf { it in LANGUAGES } ?: Locale.current.language.takeIf { it in LANGUAGES } ?: "en"

/** Maurice's words in one [language]: the iOS table, the Android-only one over it. */
suspend fun mauriceTable(language: String): Map<String, String> {
    suspend fun read(path: String) = runCatching { Strings.parse(Res.readBytes(path).decodeToString()) }.getOrDefault(emptyMap())
    return read("files/strings/$language.strings") + read("files/android/$language.strings")
}

/**
 * The Maurice app: pair a household, choose who you are, talk. Each
 * platform hands in its [Session] (Android: Keystore; desktop: a file).
 */
@Composable
fun MauriceApp(session: Session) {
    // The member's own language and look, else the device's.
    val prefs by session.prefs.collectAsState()
    val strings by produceState<Strings?>(null, prefs.locale) { value = loadMauriceStrings(prefs.locale) }
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    // The pulse on the activity line: a device preference, chosen in Settings.
    val pulse = remember(session) { eu.chezmaurice.core.settings.ThinkingPulsePreference(session.store) }
    eu.chezmaurice.maurice.onboarding.InvitationLinks.Handle(session)
    eu.chezmaurice.maurice.onboarding.HouseholdsRefresh(session)
    // A notification from another household: go there first, the home screen opens the conversation.
    val opening by OpenRequest.pending.collectAsState()
    LaunchedEffect(opening) {
        val tag = opening?.householdTag ?: return@LaunchedEffect
        if (tag != state.currentId && state.households.any { it.id == tag }) session.selectHousehold(tag)
    }

    MauriceThemeProvider(prefs.palette) {
        // Side insets here (navigation bar and cutout in landscape); each
        // screen handles top and bottom itself.
        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            val loaded = strings
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Box
            }
            CompositionLocalProvider(LocalStrings provides loaded, eu.chezmaurice.maurice.chat.LocalThinkingPulse provides pulse) {
                val household = state.current
                val member = state.activeMember
                // Asked once per server, of members who do not run it.
                var disclosed by remember(household?.id, member?.id) { mutableStateOf(!session.needsConnectDisclosure()) }
                when {
                    household == null -> PairingScreen(session)
                    member == null -> MemberPickerScreen(session, household)
                    !disclosed -> ConnectDisclosure(
                        onAccept = { session.acceptConnectDisclosure(); disclosed = true },
                        onCancel = { scope.launch { session.disconnect(household.id) } },
                    )
                    // Built again for each member of each household: nothing of one is shown to the next.
                    // The address too: a household that moved builds its clients again there.
                    else -> key(household.id, member.id, household.serverUrl) { HomeScreen(session, household, member) }
                }
                // A sign-up link while a household is already here (the welcome
                // screen takes it otherwise): the sign-up, over what is shown.
                if (household != null) {
                    var signupCode by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        eu.chezmaurice.maurice.onboarding.AccessCodeLinks.pending.collect { code ->
                            if (code != null) { eu.chezmaurice.maurice.onboarding.AccessCodeLinks.pending.value = null; signupCode = code }
                        }
                    }
                    signupCode?.let { code ->
                        eu.chezmaurice.maurice.onboarding.SignupDialog(session, code = code, onJoined = { signupCode = null }, onDismiss = { signupCode = null })
                    }
                }
            }
        } }
    }
}
