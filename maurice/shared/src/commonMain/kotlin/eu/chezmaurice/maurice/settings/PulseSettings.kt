package eu.chezmaurice.maurice.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.settings.ThinkingPulseStyle
import eu.chezmaurice.maurice.chat.LocalThinkingPulse
import eu.chezmaurice.maurice.chat.ThinkingPulse
import eu.chezmaurice.maurice.chat.chosenThinkingPulse
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme

/**
 * Settings → *Activity*: the four pulses, each drawn live beside its name, to
 * try in a conversation (`SettingsView`, `settings.pulse.*`). A device
 * preference: it holds for every household and member of the device.
 */
@Composable
fun PulseGroup() {
    val theme = LocalMauriceTheme.current
    val pref = LocalThinkingPulse.current ?: return
    val chosen = chosenThinkingPulse()
    SetGroup(L("settings.pulse.group")) {
        SetCard {
            for (style in ThinkingPulseStyle.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SetCheckRow(L(style.labelKey), chosen == style) { pref.choose(style) } }
                    ThinkingPulse(style, color = theme.inkSoft, modifier = Modifier.padding(end = 14.dp))
                }
            }
        }
        SetCaption(L("settings.pulse.caption"))
    }
}
