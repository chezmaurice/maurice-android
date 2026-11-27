package eu.chezmaurice.carnet.platform

import androidx.compose.runtime.Composable

/** The Maurice app, seen from Carnet: is it here, and open one of its conversations. */
interface MauriceApp {
    val installed: Boolean
    fun open(conversationId: String)
}

/** `maurice://conversations/<id>`; none on the desktop, where Maurice is another window. */
@Composable
expect fun rememberMauriceApp(): MauriceApp
