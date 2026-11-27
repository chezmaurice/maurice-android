package eu.chezmaurice.carnet

import eu.chezmaurice.carnet.resources.Res
import eu.chezmaurice.maurice.appLanguage
import eu.chezmaurice.maurice.mauriceTable
import eu.chezmaurice.ui.Strings
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Carnet's words in [locale] (null: the device's). Maurice's table first —
 * Carnet borrows its screens for pairing, the member picker and the chat —
 * then Carnet's own over it: the words Carnet iOS has no key for
 * (`files/android`), then Carnet iOS's table (`files/strings`, copied from
 * its Localizable.xcstrings by `scripts/sync-strings.sh`), which wins on a
 * key both apps hold, as the Carnet iOS app says its own words. English
 * underneath all of it.
 */
suspend fun loadCarnetStrings(locale: String?): Strings {
    val language = appLanguage(locale)
    val english = carnetTable("en")
    return Strings(if (language == "en") english else carnetTable(language), english)
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun carnetTable(language: String): Map<String, String> {
    suspend fun read(path: String) = runCatching { Strings.parse(Res.readBytes(path).decodeToString()) }.getOrDefault(emptyMap())
    return mauriceTable(language) + read("files/android/$language.strings") + read("files/strings/$language.strings")
}
