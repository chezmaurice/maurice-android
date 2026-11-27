package eu.chezmaurice.core.store

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * A properties file in the user's configuration folder, readable by its owner
 * only. Secrets sit beside the rest: the folder's permissions are the
 * protection on desktop for now (the OS keychain is a later step).
 */
class FileKeyValueStore(appName: String) : KeyValueStore {
    private val file: File = configDir(appName).resolve("settings.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)

    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        save()
    }

    override fun getSecret(key: String): String? = get("secret.$key")
    override fun putSecret(key: String, value: String?) = put("secret.$key", value)

    private fun save() {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        runCatching {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        tmp.renameTo(file)
    }

    companion object {
        fun configDir(appName: String): File {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", appName)
                // Development runs only: the Mac is not a target.
                os.contains("mac") -> File("$home/Library/Application Support", appName)
                else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", appName.lowercase())
            }
        }
    }
}
