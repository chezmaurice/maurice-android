package eu.chezmaurice.core.domains

/**
 * Two names in the order the list shows them, as Swift's
 * `localizedCaseInsensitiveCompare` puts them: by letter, case aside, an
 * accent counting only between names otherwise alike ("Ébène" before "Echo").
 */
expect fun compareNames(a: String, b: String): Int
