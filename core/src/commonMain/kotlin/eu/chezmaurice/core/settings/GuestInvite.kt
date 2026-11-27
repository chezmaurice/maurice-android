package eu.chezmaurice.core.settings

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.MauriceInvitation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

/**
 * A friend from another household, invited here as a guest by a member
 * (specs/cross-household-share.md, section A; `GuestInvite.swift`): a
 * single-use code whose redeemer becomes a guest who sees only the inviter,
 * and whom only the inviter can share with.
 *
 * The one place the app speaks to the route — `POST /api/me/guest-invite`
 * → `{ code, link?, expires_at }`, the shape of `/api/me/device-code`.
 */
object GuestInvite {
    @Serializable
    data class Code(
        val code: String,
        /** The page to send; built from the server and the code when absent. */
        val link: String? = null,
        @SerialName("expires_at") val expiresAt: String,
    ) {
        fun invitation(serverUrl: String): Invitation =
            link?.let(MauriceInvitation::parse) ?: Invitation(serverUrl, code)
    }

    sealed interface Outcome {
        data class Issued(val code: Code) : Outcome
        /**
         * The server says this member may not (a guest, a child the admin has
         * not allowed, an API token) or does not know the route yet: the row
         * goes away.
         */
        data object NotAllowed : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    suspend fun create(api: ApiClient, serverUrl: String, memberId: String): Outcome = try {
        Outcome.Issued(api.post<Code>("/api/me/guest-invite", buildJsonObject {}))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ApiError) {
        if (e.status == 403 || e.status == 404) {
            refused += "$serverUrl|$memberId"
            Outcome.NotAllowed
        } else Outcome.Failed(e)
    } catch (e: Throwable) {
        Outcome.Failed(e)
    }

    /**
     * Members the server refused during this run of the app: the row stays
     * hidden for them until the app restarts (an admin may allow a child later).
     */
    fun isRefused(serverUrl: String?, memberId: String?): Boolean =
        serverUrl != null && memberId != null && "$serverUrl|$memberId" in refused

    private val refused = mutableSetOf<String>()

    /** Tests: a fresh run of the app. */
    fun forgetRefusals() = refused.clear()
}
