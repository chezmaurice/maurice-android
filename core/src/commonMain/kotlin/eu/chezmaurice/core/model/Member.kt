package eu.chezmaurice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A household member as the server returns it. `GET /api/users` gives admins
 * the full user and everyone else a subset (no `username`), hence the defaults.
 */
@Serializable
data class Member(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_color") val avatarColor: String = "#6b6460",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("has_pin") val hasPin: Boolean = false,
    val role: Role? = null,
    val username: String? = null,
    @SerialName("is_child") val isChild: Boolean = false,
) {
    val initial: String get() = displayName.trim().take(1).uppercase()
}

@Serializable
enum class Role {
    @SerialName("admin") Admin,
    @SerialName("standard") Standard,
    @SerialName("guest") Guest,
}
