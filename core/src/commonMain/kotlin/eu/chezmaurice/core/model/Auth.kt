package eu.chezmaurice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/health` — open, no auth. */
@Serializable
data class Health(
    val status: String,
    val version: String? = null,
    @SerialName("setup_complete") val setupComplete: Boolean = true,
    val household: String? = null,
    @SerialName("household_color") val householdColor: String? = null,
    @SerialName("household_icon") val householdIcon: String? = null,
    /** The household's Ed25519 public key, 32 raw bytes in base64url: pinned at pairing. */
    @SerialName("identity_key") val identityKey: String? = null,
    /** With `?proof=<nonce>`: the household's signature of the nonce and of its own address, base64url (see HouseholdMove). */
    val proof: String? = null,
    /** The address that signature is for: the household's own, `https://host[:port]`. */
    @SerialName("proof_origin") val proofOrigin: String? = null,
)

/** `POST /api/auth/login` and the token half of the other sign-ins. */
@Serializable
data class LoginResponse(
    @SerialName("user_id") val userId: String,
    val token: String,
)

/**
 * `POST /api/auth/enroll`. An open household invitation first answers
 * `{needs_profile, household}`; called again with a name, it enrols.
 */
@Serializable
data class EnrollResponse(
    @SerialName("needs_profile") val needsProfile: Boolean = false,
    val household: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val token: String? = null,
    val role: Role? = null,
    @SerialName("needs_pin") val needsPin: Boolean = false,
)

/** `POST /api/auth/setup` — first run, creates the admin. */
@Serializable
data class SetupResponse(val user: Member, val token: String)
