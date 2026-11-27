package eu.chezmaurice.carnet.platform

import java.io.File

actual object Offline {
    /** Set by the application at start: its files directory. */
    lateinit var root: File

    private fun file(key: String) = File(File(root, "offline").apply { mkdirs() }, "$key.json")
    actual fun read(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()
    actual fun write(key: String, text: String) { runCatching { file(key).writeText(text) } }
    actual fun delete(key: String) { runCatching { file(key).delete() } }
    actual fun exists(key: String): Boolean = file(key).exists()
    private fun under(prefix: String) = (File(root, "offline").apply { mkdirs() }).listFiles { f -> f.name.startsWith(prefix) }.orEmpty()
    actual fun deleteAll(prefix: String) { under(prefix).forEach { it.delete() } }
    actual fun sizeOf(prefix: String): Long = under(prefix).sumOf { it.length() }
    private fun blob(key: String) = File(file(key).parentFile, "$key.bin")
    actual fun readBytes(key: String): ByteArray? = runCatching { blob(key).takeIf { it.exists() }?.readBytes() }.getOrNull()
    actual fun writeBytes(key: String, bytes: ByteArray) { runCatching { blob(key).writeBytes(bytes) } }
}
