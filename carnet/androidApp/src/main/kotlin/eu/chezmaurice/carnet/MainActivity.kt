package eu.chezmaurice.carnet

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.chezmaurice.carnet.platform.Offline
import android.net.Uri
import android.provider.OpenableColumns
import eu.chezmaurice.carnet.share.CarnetShare
import eu.chezmaurice.carnet.share.SharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.chezmaurice.core.session.AndroidPairings
import eu.chezmaurice.core.session.PairingsChannels
import eu.chezmaurice.core.push.householdToSwitchTo
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.AndroidKeyValueStore

/** Holds the session for the life of the process, across activity restarts. */
class CarnetApplication : Application() {
    /** The households shared with the Maurice app: joining, signing in or forgetting one here is the same there. */
    private val pairings by lazy { AndroidPairings(this, PairingsChannels.other(packageName)) }
    val session: Session by lazy { Session(AndroidKeyValueStore(this, "carnet"), prefix = "carnet", shared = pairings) }

    override fun onCreate() {
        super.onCreate()
        Offline.root = filesDir
        // The Maurice app changed something: taken at once.
        pairings.observe { session.applyShared() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as CarnetApplication).session
        // Recreated (a rotation), or reopened from the recent apps: the intent is
        // the first one again, already taken — its invitation used, its
        // notification opened. Taking it again would enrol a second time
        // (22 November 2026).
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) take(intent)
        setContent { CarnetApp(session) }
    }

    override fun onResume() {
        super.onResume()
        // Back in front: what the Maurice app changed meanwhile.
        (application as CarnetApplication).session.applyShared()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** `carnet://entry/<collection>/<locale>/<slug>`, from the Maurice app; or a page shared by another app. */
    private fun take(intent: Intent?) {
        // An invitation (https://<foyer>/join?code=…, carnet://join?…), else an entry from the Maurice app.
        intent?.data?.let { if (eu.chezmaurice.maurice.onboarding.InvitationLinks.open(it.toString(), eu.chezmaurice.maurice.onboarding.AppBrand.Carnet)) intent.data = null }
        intent?.data?.takeIf { it.scheme == "carnet" }?.let { CarnetRouter.open(it.toString()) }
        takeNotification(intent)
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            CarnetShare.offer(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        } else if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: return
            CarnetShare.offerFile(sharedFile(uri, intent.type))
        }
    }

    /**
     * A notification's tap (CarnetPushService): to the household it came from,
     * as iOS does, then the conversation Maurice opened or the entry shared.
     * The extras are taken off too.
     */
    private fun takeNotification(intent: Intent?) {
        intent ?: return
        val conversation = intent.getStringExtra(CarnetPushService.EXTRA_CONVERSATION)
        val entry = intent.getStringExtra(CarnetPushService.EXTRA_ENTRY)
        if (!intent.hasExtra(CarnetPushService.EXTRA_HOUSEHOLD) && conversation == null && entry == null) return
        val session = (application as CarnetApplication).session
        val state = session.state.value
        val tag = intent.getStringExtra(CarnetPushService.EXTRA_HOUSEHOLD)
        householdToSwitchTo(tag, state.households.map { it.id }, state.currentId)?.let { session.selectHousehold(it) }
        // Only a household paired here: a tag forgotten since would hold the conversation forever.
        val paired = tag?.takeIf { t -> state.households.any { it.id == t } }
        when {
            conversation != null -> CarnetRouter.openConversation(paired, conversation)
            entry != null -> CarnetRouter.openSharedEntry(entry)
        }
        intent.removeExtra(CarnetPushService.EXTRA_CONVERSATION)
        intent.removeExtra(CarnetPushService.EXTRA_ENTRY)
        intent.removeExtra(CarnetPushService.EXTRA_HOUSEHOLD)
    }

    /** A shared stream, named as the sending app names it — with the extension its type implies when it gave none: the extension is how a book is told. */
    private fun sharedFile(uri: Uri, type: String?): SharedFile {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
        var size: Long? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = type ?: contentResolver.getType(uri)
        if (!name.contains('.')) when (mime) {
            "application/epub+zip" -> name += ".epub"
            "application/pdf" -> name += ".pdf"
        }
        val resolver = contentResolver
        return SharedFile(name, size) {
            withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("Unreadable file") }
        }
    }
}
