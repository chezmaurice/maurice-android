package eu.chezmaurice.carnet.platform

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import eu.chezmaurice.ui.L

@Composable
actual fun HighlightMenu(available: Boolean, onHighlight: () -> Unit, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(onHighlight)
    val offered by rememberUpdatedState(available)
    val label = L("carnet.highlight.action")
    ContextMenuDataProvider(items = { if (offered) listOf(ContextMenuItem(label) { current() }) else emptyList() }) { content() }
}
