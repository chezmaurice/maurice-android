package eu.chezmaurice.maurice.platform

import java.util.Locale

actual fun countryName(regionCode: String, language: String): String? =
    runCatching { Locale.Builder().setRegion(regionCode).build().getDisplayCountry(Locale.forLanguageTag(language)) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: regionCode.takeIf { it.isNotBlank() }
