package eu.chezmaurice.carnet.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import eu.chezmaurice.ui.L
import androidx.compose.ui.Modifier

private object HighlightKey

@Composable
actual fun HighlightMenu(available: Boolean, onHighlight: () -> Unit, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(onHighlight)
    val offered by rememberUpdatedState(available)
    val label = L("carnet.highlight.action")
    Box(Modifier.appendTextContextMenuComponents {
        if (offered) item(HighlightKey, label) { current(); close() }
    }) { content() }
}
