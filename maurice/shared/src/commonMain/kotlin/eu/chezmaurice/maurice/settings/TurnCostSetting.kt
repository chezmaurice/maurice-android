package eu.chezmaurice.maurice.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.chezmaurice.core.store.KeyValueStore
import eu.chezmaurice.maurice.chat.TurnCostPref

/**
 * USAGE — "Show what each reply cost" (SettingsView). iOS writes the group,
 * the row and its caption in English, unkeyed; so does this.
 */
@Composable
internal fun TurnCostGroup(store: KeyValueStore) {
    val pref = remember(store) { TurnCostPref.of(store) }
    val on by pref.on.collectAsState()
    SetGroup("USAGE") {
        SetCard { SetCheckRow("Show what each reply cost", on) { pref.set(!on) } }
        SetCaption("Adds a small line under each of Maurice's replies with the cost of that turn and how much of the prompt was served from cache. Local models are free; cloud models show a figure when their price is on file.")
    }
}
