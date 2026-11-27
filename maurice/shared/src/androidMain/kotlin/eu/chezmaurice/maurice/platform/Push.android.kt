package eu.chezmaurice.maurice.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import eu.chezmaurice.core.session.Session
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Where the app keeps the subscription the distributor gave it, to declare it
 * again on each start. Per app: Maurice and Carnet each have their own
 * preferences, so each keeps its own registration.
 */
object PushPrefs {
    private const val FILE = "maurice.push"
    fun save(context: Context, url: String, p256dh: String, auth: String) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("url", url).putString("p256dh", p256dh).putString("auth", auth).apply()
    fun clear(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    fun load(context: Context): Triple<String, String, String>? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Triple(p.getString("url", null) ?: return null, p.getString("p256dh", null) ?: return null, p.getString("auth", null) ?: return null)
    }
}

@Composable
actual fun PushRegistrationEffect(session: Session, platform: String, appName: String) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(session.state.value.activeMember?.id) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        // A subscription already in hand: declare it again (a new member, a new household).
        PushPrefs.load(context)?.let { (url, p256dh, auth) -> session.registerPush(url, p256dh, auth, platform) }
        // Ask the distributor (ntfy) for one; it answers in the app's PushService.onNewEndpoint.
        val activity = context as? Activity ?: return@LaunchedEffect
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(context, messageForDistributor = appName)
        }
    }
}
