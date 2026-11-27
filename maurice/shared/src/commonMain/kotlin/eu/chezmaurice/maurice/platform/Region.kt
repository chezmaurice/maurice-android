package eu.chezmaurice.maurice.platform

/** A country's name from its ISO region code ("FR" → "France"), in the given language; null when unknown. */
expect fun countryName(regionCode: String, language: String): String?
