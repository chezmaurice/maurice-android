package eu.chezmaurice.core.store

/**
 * Where the app keeps what it must remember across launches. Each platform
 * supplies one: Android encrypts secrets with a Keystore key, the desktop
 * keeps a file only its user can read.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    /** Like [get]/[put], for tokens: encrypted at rest where the platform allows. */
    fun getSecret(key: String): String?
    fun putSecret(key: String, value: String?)
}

/** For tests and previews. */
class MemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun getSecret(key: String) = secrets[key]
    override fun putSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
}
