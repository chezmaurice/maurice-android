package eu.chezmaurice.maurice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.chezmaurice.core.push.PushNotice
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
 * UnifiedPush's side of the app: the distributor (ntfy) hands it an endpoint
 * and Web Push keys, which go to the households; then messages, decrypted by
 * the connector, become notifications. No Google anywhere.
 */
class MauricePushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session get() = (application as MauriceApplication).session

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: run { Log.w(TAG, "endpoint without Web Push keys: not used"); return }
        PushPrefs.save(this, endpoint.url, keys.pubKey, keys.auth)
        scope.launch { session.registerPush(endpoint.url, keys.pubKey, keys.auth) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) { Log.w(TAG, "message not decrypted: dropped"); return }
        val notice = PushNotice.parse(message.content.decodeToString()) ?: return
        notify(notice.title ?: "Maurice", notice.body, notice.conversationId, notice.householdTag)
    }

    override fun onUnregistered(instance: String) {
        val old = PushPrefs.load(this)?.first
        PushPrefs.clear(this)
        if (old != null) scope.launch { session.unregisterPush(old) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "registration failed: $reason")
    }

    private fun notify(title: String, body: String, conversationId: String?, householdTag: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notifications_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONVERSATION, conversationId)
            putExtra(EXTRA_HOUSEHOLD, householdTag)
        }
        val tap = PendingIntent.getActivity(this, (conversationId ?: "").hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify((conversationId ?: title).hashCode(), notification) }
    }

    companion object {
        private const val TAG = "maurice.push"
        private const val CHANNEL = "messages"
        const val EXTRA_CONVERSATION = "eu.chezmaurice.maurice.conversation"
        const val EXTRA_HOUSEHOLD = "eu.chezmaurice.maurice.household"
    }
}
