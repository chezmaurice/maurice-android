package eu.chezmaurice.maurice

import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.ui.Strings

/** What to tell the member when something failed. */
fun Strings.describe(error: Throwable): String = when {
    // A route the household does not have yet: older than this app (22 November 2026).
    error is ApiError && error.isUnknownRoute && has("chat.error.household_older") -> this("chat.error.household_older")
    error is ApiError && error.message == "invalid_address" -> this("pairing.error.invalid_url")
    error is ApiError && error.message == "not_a_maurice_server" -> this("pairing.error.status")
    error is ApiError && has(error.message) -> this(error.message)
    error is ApiError && error.status != 0 -> error.shown
    else -> this("pairing.error.connect", error.message ?: this("pairing.error.unreachable"))
}

/** Server paths (`/api/avatars/…`, `/api/images/…`) made absolute. */
fun absoluteUrl(serverUrl: String, path: String?): String? = when {
    path.isNullOrBlank() -> null
    path.startsWith("http://") || path.startsWith("https://") -> path
    else -> serverUrl.trimEnd('/') + "/" + path.trimStart('/')
}
