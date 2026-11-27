package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable

/**
 * While composed, the status bar's icons are light (for a dark ground behind
 * them, whatever the theme); back to the theme's when it leaves. Nothing on
 * the desktop, which has no status bar.
 */
@Composable
expect fun LightStatusBarIcons()
