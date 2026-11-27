package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.UsageFormat
import eu.chezmaurice.core.chat.saved
import eu.chezmaurice.core.model.TurnUsage
import eu.chezmaurice.core.store.KeyValueStore
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.MauriceIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Show what each reply cost" (`TurnCostPref`): off unless the member asks.
 * The device's, as iOS keeps it in its defaults, not the member's: one switch
 * for every household and member of this app. The chat's coin, the details'
 * *Cost and tokens* and the Settings row read the same instance.
 */
class TurnCostPref private constructor(private val store: KeyValueStore) {
    private val _on = MutableStateFlow(store.get(KEY) == "true")
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun set(value: Boolean) {
        store.put(KEY, if (value) "true" else null)
        _on.value = value
    }

    companion object {
        const val KEY = "maurice.showTurnCost"
        private var shared: TurnCostPref? = null

        /** The one switch for [store]: the app has a single store. */
        fun of(store: KeyValueStore): TurnCostPref =
            shared?.takeIf { it.store === store } ?: TurnCostPref(store).also { shared = it }
    }
}

val LocalTurnCost = staticCompositionLocalOf<TurnCostPref?> { null }

/**
 * What a new installation starts with, where it differs from what an
 * installation already in use keeps (`FreshInstallDefaults`, 22 November
 * 2026): the cost of each turn shown. Written once, as a stored value, the
 * first time the app runs on a device that has never held a household — so
 * nobody's settings change under them with an update, and it can still be
 * turned off in Settings. To call before the [eu.chezmaurice.core.session.Session]
 * is made (it makes the device id).
 *
 * The other half on iOS, dictation transcribed by the household's server, is
 * already so on Android and the desktop: they have no on-device engine and
 * always send the recording to the household (which answers `allowed: false`
 * until a hosted household's member has agreed to its AI provider).
 */
object FreshInstallDefaults {
    private const val MARKER = "maurice.freshDefaults.v1"

    fun applyOnce(store: KeyValueStore) {
        if (store.get(MARKER) != null) return
        store.put(MARKER, "1")
        // An installation already in use: it has a device id or households.
        if (store.get("device.id") != null || store.get("maurice.households") != null) return
        if (store.get(TurnCostPref.KEY) == null) store.put(TurnCostPref.KEY, "true")
    }
}

/**
 * What a turn cost, in the row under the reply (`TurnUsageFooter`): a coin,
 * the cost (or the token volume when the model has no price), the share of
 * the prompt served from cache. A tap drops down the token split and what the
 * turn would have cost uncached, over the thread rather than in it. iOS writes
 * these words in English, unkeyed; so does this.
 */
@Composable
internal fun TurnUsageFooter(usage: TurnUsage) {
    val theme = LocalMauriceTheme.current
    val language = LocalAppLanguage.current
    var open by remember { mutableStateOf(false) }
    val headline = UsageFormat.headline(usage, language)
    val cache = UsageFormat.cacheLabel(usage)
    androidx.compose.foundation.layout.Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 6.dp)
                .semantics { contentDescription = "What this turn cost" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(MauriceIcons.Coin, null, tint = theme.inkMute, modifier = Modifier.size(13.dp))
            Text(headline, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = theme.inkMute)
            if (cache != null) {
                Text("·", fontSize = 11.sp, color = theme.inkMute)
                Text(cache, fontSize = 11.sp, color = theme.inkMute)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = theme.surface) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FooterRow("Cost", headline)
                FooterRow("Prompt", "${UsageFormat.tokens(usage.input)} fresh · ${UsageFormat.tokens(usage.cacheRead)} from cache · ${UsageFormat.tokens(usage.cacheWrite)} written")
                FooterRow("Reply", "${UsageFormat.tokens(usage.output)} tok")
                FooterRow("Rounds", "${usage.rounds}")
                val uncached = usage.costUncached
                if (uncached != null && usage.cost != null) {
                    FooterRow("Without cache", UsageFormat.money(uncached, language))
                    usage.saved?.takeIf { it > 0 }?.let { FooterRow("Saved", UsageFormat.money(it, language)) }
                }
                FooterRow("Model", usage.model)
            }
        }
    }
}

@Composable
private fun FooterRow(label: String, value: String) {
    val theme = LocalMauriceTheme.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 11.sp, color = theme.inkMute, modifier = Modifier.width(92.dp))
        Text(value, fontSize = 11.sp, color = theme.inkSoft, modifier = Modifier.widthIn(max = 240.dp))
    }
}
