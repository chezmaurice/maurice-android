package eu.chezmaurice.carnet.platform

import androidx.compose.runtime.Composable

/**
 * "Highlight" in the menu a text selection shows, beside Copy: Android's
 * floating toolbar, the desktop's right-click menu. Only over a selection
 * ([available]): a bare cursor has nothing to mark.
 */
@Composable
expect fun HighlightMenu(available: Boolean, onHighlight: () -> Unit, content: @Composable () -> Unit)
