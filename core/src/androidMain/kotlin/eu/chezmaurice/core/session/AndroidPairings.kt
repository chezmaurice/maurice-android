package eu.chezmaurice.core.session

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.store.AndroidKeyValueStore

/**
 * The households the Maurice app and Carnet share on Android (SharedPairings).
 * Each app keeps the last pairings it wrote or read, sealed in a store of its
 * own, and serves them from [PairingsProvider] to the other app alone: the
 * provider asks for [PERMISSION], of signature level, which only an app
 * signed with the same key holds. Writing tells the other app by a broadcast
 * to its package ([ACTION], same permission); it reads the provider then, or
 * when it comes back to the foreground, as iOS does.
 *
 * [otherPackage]: the other app of the pair, in the same build (release with
 * release, debug with debug): see [PairingsChannels.other].
 */
class AndroidPairings(private val context: Context, private val otherPackage: String) : PairingsChannel {
    private val local = AndroidKeyValueStore(context, STORE)

    override fun read(): SharedPairings? {
        val mine = stamped(local.getSecret(KEY), local.get(STAMP))
        val theirs = runCatching {
            context.contentResolver.query(Uri.parse("content://$otherPackage.pairings"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) stamped(c.getString(0), c.getString(1)) else null
            }
        }.getOrNull()
        // The newer of the two; the other's, once read, is kept here too (a third reader may ask).
        if (theirs != null && (mine == null || theirs.second > mine.second)) {
            local.putSecret(KEY, MauriceJson.encodeToString(SharedPairings.serializer(), theirs.first))
            local.put(STAMP, theirs.second.toString())
            return theirs.first
        }
        return mine?.first
    }

    override fun write(pairings: SharedPairings) {
        local.putSecret(KEY, MauriceJson.encodeToString(SharedPairings.serializer(), pairings))
        local.put(STAMP, System.currentTimeMillis().toString())
        runCatching { context.sendBroadcast(Intent(ACTION).setPackage(otherPackage), PERMISSION) }
    }

    /** [changed] on the main thread whenever the other app says it wrote; the returned function stops listening. */
    fun observe(changed: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changed()
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter, PERMISSION, null)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun stamped(json: String?, stamp: String?): Pair<SharedPairings, Long>? {
        val pairings = json?.let { runCatching { MauriceJson.decodeFromString(SharedPairings.serializer(), it) }.getOrNull() } ?: return null
        return pairings to (stamp?.toLongOrNull() ?: 0L)
    }

    companion object {
        const val PERMISSION = "eu.chezmaurice.permission.PAIRINGS"
        const val ACTION = "eu.chezmaurice.action.PAIRINGS_CHANGED"
        internal const val STORE = "shared-pairings"
        internal const val KEY = "pairings"
        internal const val STAMP = "pairings.stamp"
    }
}

object PairingsChannels {
    /** The other app of the pair, in the same build: Maurice ↔ Carnet, a debug build's suffix kept. */
    fun other(packageName: String): String = when {
        packageName.startsWith("eu.chezmaurice.maurice") -> "eu.chezmaurice.carnet" + packageName.removePrefix("eu.chezmaurice.maurice")
        packageName.startsWith("eu.chezmaurice.carnet") -> "eu.chezmaurice.maurice" + packageName.removePrefix("eu.chezmaurice.carnet")
        else -> packageName
    }
}

/**
 * The pairings this app last wrote or read, for the other app of the pair
 * only (`readPermission` of signature level, in core's manifest).
 */
class PairingsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val store = AndroidKeyValueStore(context!!, AndroidPairings.STORE)
        return MatrixCursor(arrayOf("pairings", "stamp")).apply {
            val json = store.getSecret(AndroidPairings.KEY)
            if (json != null) addRow(arrayOf(json, store.get(AndroidPairings.STAMP) ?: "0"))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
