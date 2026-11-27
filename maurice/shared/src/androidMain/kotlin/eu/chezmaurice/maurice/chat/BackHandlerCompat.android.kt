package eu.chezmaurice.maurice.chat

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun BackHandlerCompat(onBack: () -> Unit) = BackHandler(onBack = onBack)
