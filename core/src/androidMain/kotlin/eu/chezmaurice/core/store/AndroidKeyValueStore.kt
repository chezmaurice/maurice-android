package eu.chezmaurice.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences, with secrets sealed by an AES-GCM key that never leaves
 * the Android Keystore. Plain Keystore rather than androidx.security-crypto,
 * which is deprecated.
 *
 * Some devices have a Keystore that fails (old Samsungs, keys invalidated by
 * a lock-screen change). Then a secret is kept unsealed in the app's private
 * preferences — still out of other apps' reach — rather than lost, which
 * would sign the member out at every launch.
 */
class AndroidKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "$name.secrets"

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun getSecret(key: String): String? {
        prefs.getString("plain.$key", null)?.let { return it }
        val sealed = prefs.getString("secret.$key", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "keystore: could not open $key", it) }.getOrNull()
    }

    override fun putSecret(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove("secret.$key").remove("plain.$key").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("secret.$key", Base64.encodeToString(sealed, Base64.NO_WRAP)).remove("plain.$key").apply()
        } catch (e: Exception) {
            Log.w(TAG, "keystore unusable, keeping $key unsealed", e)
            prefs.edit().putString("plain.$key", value).remove("secret.$key").apply()
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "maurice.store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
