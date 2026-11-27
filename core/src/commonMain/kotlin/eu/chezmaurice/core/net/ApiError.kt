package eu.chezmaurice.core.net

/**
 * A failed call. The server answers errors as `{"error": string}`: [message]
 * is that string when it did ([fromServer]), else the raw body (as Carnet
 * iOS's GardenAPI shows it) or the status's reason. Status 0 is the app's own
 * error, its words in [message].
 */
class ApiError(
    val status: Int,
    override val message: String,
    /** Seconds, from `Retry-After` on a 429. */
    val retryAfter: Int? = null,
    /** [message] is the server's own `error`. */
    val fromServer: Boolean = false,
    /** The server's `code`, when it gives one (`password_wrong`, `ai_consent_required`…). */
    val code: String? = null,
) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401

    /**
     * A 401 that says the session is over. Not one that refuses a password
     * typed to confirm something (`password_wrong`: the admin's current
     * password when changing it) — the member is still signed in.
     */
    val endsSession: Boolean get() = status == 401 && code != "password_wrong"
    val isRateLimited: Boolean get() = status == 429

    /**
     * The household's server has no such route: it is older than this app
     * (`404 {"code":"unknown_route"}`). Said as "not yet on your Maurice",
     * never as a failure (22 November 2026).
     */
    val isUnknownRoute: Boolean get() = status == 404 && code == "unknown_route"

    /**
     * What the Maurice app shows of it, as iOS does (`APIError.server`'s
     * `errorDescription`): "[402] Spending limit reached.", "[500] Request
     * failed" when the server said nothing readable; the app's own words alone.
     */
    val shown: String get() = if (status == 0) message else "[$status] " + (if (fromServer) message else "Request failed")
}

/** What the Maurice app shows of any failure: an [ApiError] as iOS words it, else its message. */
fun Throwable.shownMessage(): String? = (this as? ApiError)?.shown ?: message
