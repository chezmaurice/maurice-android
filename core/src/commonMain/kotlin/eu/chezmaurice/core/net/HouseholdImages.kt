package eu.chezmaurice.core.net

import io.ktor.http.Url

/**
 * The member's session on a picture's request — when, and only when, the
 * picture is the household's own (`SessionImage` on iOS, 28 November 2026).
 * A photo sent in a conversation (`/api/images/…`) is its participants': the
 * household answers whoever takes part in a conversation that names it, and
 * may be set to answer nobody else. An address anywhere else — a cover on a
 * provider's CDN — gets no header: the session is the household's to see.
 *
 * The apps keep [current] up to date with the household on screen and its
 * active member's token; their image loader asks [authorization] for each
 * request.
 */
object HouseholdImages {
    /** The household on screen and the active member's token; null when signed out. */
    @kotlin.concurrent.Volatile
    var current: Pair<String, String>? = null

    /** `Bearer <token>` for a picture of the household on screen, else null. */
    fun authorization(url: String): String? {
        val (server, token) = current ?: return null
        return if (sameOrigin(url, server)) "Bearer $token" else null
    }

    /** The same scheme, host and port: the household's origin, nothing near it. */
    fun sameOrigin(url: String, server: String): Boolean {
        val a = runCatching { Url(url) }.getOrNull() ?: return false
        val b = runCatching { Url(server) }.getOrNull() ?: return false
        if (a.host.isEmpty() || b.host.isEmpty()) return false
        return a.protocol.name.equals(b.protocol.name, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.port == b.port
    }
}
