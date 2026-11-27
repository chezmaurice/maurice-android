package eu.chezmaurice.carnet.platform

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberMauriceApp(): MauriceApp {
    val context = LocalContext.current
    return remember(context) {
        object : MauriceApp {
            // The Maurice app of the same build (release with release, debug with debug):
            // never a chooser between two, and the one that shares this app's households.
            private fun intent(id: String) = Intent(Intent.ACTION_VIEW, Uri.parse("maurice://conversations/$id"))
                .setPackage(eu.chezmaurice.core.session.PairingsChannels.other(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            override val installed: Boolean
                get() = context.packageManager.queryIntentActivities(intent("probe"), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
            override fun open(conversationId: String) { runCatching { context.startActivity(intent(conversationId)) } }
        }
    }
}
