package eu.chezmaurice.maurice.chat

import androidx.compose.runtime.Composable

/** The system back gesture where there is one (Android); nothing on desktop. */
@Composable
expect fun BackHandlerCompat(onBack: () -> Unit)
