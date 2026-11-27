package eu.chezmaurice.maurice

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.chezmaurice.core.session.DeviceInfo
import eu.chezmaurice.core.session.AndroidPairings
import eu.chezmaurice.core.session.PairingsChannels
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.chat.OpenRequest
import eu.chezmaurice.maurice.chat.OpenTarget
import eu.chezmaurice.core.store.AndroidKeyValueStore

/** Holds the session for the life of the process, across activity restarts. */
class MauriceApplication : Application() {
    /** The households shared with Carnet: joining, signing in or forgetting one here is the same there. */
    private val pairings by lazy { AndroidPairings(this, PairingsChannels.other(packageName)) }
    val session: Session by lazy {
        val store = AndroidKeyValueStore(this, "maurice")
        eu.chezmaurice.maurice.chat.FreshInstallDefaults.applyOnce(store)
        Session(store, prefix = "maurice", device = deviceInfo(), shared = pairings)
    }

    override fun onCreate() {
        super.onCreate()
        // Carnet changed something: taken at once.
        pairings.observe { session.applyShared() }
    }

    /** What the household's server is told this app runs on. */
    private fun deviceInfo(): DeviceInfo {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val version = info?.versionName?.let { name ->
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "$name ($code)"
        }
        return DeviceInfo(platform = "Android", model = Build.MODEL, osVersion = Build.VERSION.RELEASE, app = "maurice", appVersion = version)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as MauriceApplication).session
        // Recreated (a rotation), or reopened from the recent apps: the intent is
        // the first one again, already taken — its invitation used, its share
        // or notification opened. Taking it again would enrol a second time
        // (22 November 2026).
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) takeIntent(intent)
        setContent { MauriceApp(session) }
    }

    override fun onResume() {
        super.onResume()
        // Back in front: what Carnet changed meanwhile (a broadcast may not have reached a stopped app).
        (application as MauriceApplication).session.applyShared()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    /** A share from another app, or a notification's tap. */
    private fun takeIntent(intent: Intent?) {
        takeShare(this, intent)
        // An invitation: https://<foyer>/join?code=… (App Link) or maurice://join?server=…&code=….
        intent?.data?.let { if (eu.chezmaurice.maurice.onboarding.InvitationLinks.open(it.toString(), eu.chezmaurice.maurice.onboarding.AppBrand.Maurice)) intent.data = null }
        // An access code, for a Maurice still to be made: maurice://signup?code=… (the tower's "Open" button).
        intent?.data?.let { if (eu.chezmaurice.maurice.onboarding.AccessCodeLinks.open(it.toString())) intent.data = null }
        // maurice://conversations/<id>, from Carnet: in the household on screen.
        intent?.data?.takeIf { it.scheme == "maurice" && it.host == "conversations" }?.lastPathSegment?.let { id ->
            OpenRequest.put(OpenTarget(null, id))
            intent.data = null
        }
        intent?.getStringExtra(MauricePushService.EXTRA_CONVERSATION)?.let { id ->
            OpenRequest.put(OpenTarget(intent.getStringExtra(MauricePushService.EXTRA_HOUSEHOLD), id))
            intent.removeExtra(MauricePushService.EXTRA_CONVERSATION)
        }
    }
}
