package eu.chezmaurice.carnet.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberMauriceApp(): MauriceApp = object : MauriceApp {
    override val installed = false
    override fun open(conversationId: String) {}
}
