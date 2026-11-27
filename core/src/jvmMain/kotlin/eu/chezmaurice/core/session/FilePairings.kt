package eu.chezmaurice.core.session

import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.store.FileKeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The households the Maurice app and Carnet share on Windows and Linux
 * (SharedPairings): one file both read and write, beside their own settings
 * (`Chez Maurice/shared-pairings.json` in the user's config directory), kept
 * to its owner as their settings are. The other app reads it when its window
 * comes back to the front.
 */
class FilePairings(
    private val file: File = FileKeyValueStore.configDir("Chez Maurice").resolve("shared-pairings.json"),
) : PairingsChannel {
    override fun read(): SharedPairings? =
        runCatching { MauriceJson.decodeFromString(SharedPairings.serializer(), file.readText()) }.getOrNull()

    override fun write(pairings: SharedPairings) {
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(MauriceJson.encodeToString(SharedPairings.serializer(), pairings))
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
