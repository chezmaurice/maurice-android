package eu.chezmaurice.carnet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.chezmaurice.core.push.PushNotice
import eu.chezmaurice.core.push.PushTap
import eu.chezmaurice.maurice.platform.PushPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Carnet's side of UnifiedPush, as the Maurice app's MauricePushService: the
 * distributor hands this app its own endpoint and Web Push keys (Maurice,
 * installed beside it, has another), declared to the households as
 * `carnet-android`; the messages, decrypted by the connector, become
 * notifications — the conversations Maurice opens, the entries another
 * member shares (iOS: CarnetNotifications, CarnetNotificationService). No Google.
 */
class CarnetPushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session get() = (application as CarnetApplication).session

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: run { Log.w(TAG, "endpoint without Web Push keys: not used"); return }
        PushPrefs.save(this, endpoint.url, keys.pubKey, keys.auth)
        scope.launch { session.registerPush(endpoint.url, keys.pubKey, keys.auth, PLATFORM) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) { Log.w(TAG, "message not decrypted: dropped"); return }
        val notice = PushNotice.parse(message.content.decodeToString()) ?: return
        notify(notice)
    }

    override fun onUnregistered(instance: String) {
        val old = PushPrefs.load(this)?.first
        PushPrefs.clear(this)
        if (old != null) scope.launch { session.unregisterPush(old) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "registration failed: $reason")
    }

    private fun notify(notice: PushNotice) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notifications_channel), NotificationManager.IMPORTANCE_HIGH))
        val tap = notice.tap
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            when (tap) {
                is PushTap.Conversation -> putExtra(EXTRA_CONVERSATION, tap.id)
                is PushTap.Entry -> putExtra(EXTRA_ENTRY, tap.ref)
                null -> {}
            }
            putExtra(EXTRA_HOUSEHOLD, notice.householdTag)
        }
        // One notification per conversation or entry: a second push about it replaces the first.
        val key = when (tap) {
            is PushTap.Conversation -> "c:" + tap.id
            is PushTap.Entry -> "e:" + tap.ref
            null -> "t:" + notice.title + notice.body
        }.hashCode()
        val pending = PendingIntent.getActivity(this, key, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(notice.title ?: getString(R.string.app_name))
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(key, notification) }
    }

    companion object {
        private const val TAG = "carnet.push"
        private const val CHANNEL = "notifications"
        /** What the households know this device as: Carnet's (`carnet-*`), apart from the Maurice app's `android`. */
        const val PLATFORM = "carnet-android"
        const val EXTRA_CONVERSATION = "eu.chezmaurice.carnet.conversation"
        const val EXTRA_ENTRY = "eu.chezmaurice.carnet.entry"
        const val EXTRA_HOUSEHOLD = "eu.chezmaurice.carnet.household"
    }
}
