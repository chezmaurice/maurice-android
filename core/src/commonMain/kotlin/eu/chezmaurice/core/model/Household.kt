package eu.chezmaurice.core.model

import kotlinx.serialization.Serializable

/**
 * A household paired on this device. Local only — the server never sees it.
 * Tokens are not kept here but in the secret store, per household and member.
 */
@Serializable
data class Household(
    /** Local id, stable across launches. */
    val id: String,
    val serverUrl: String,
    val name: String,
    val color: String? = null,
    val icon: String? = null,
    val members: List<Member> = emptyList(),
    val activeMemberId: String? = null,
    /**
     * Its identity key (`identity_key`), pinned the first time it is seen and
     * never replaced: another key at the same address is not this household.
     * What lets the device follow it when it moves (HouseholdMove).
     */
    val key: String? = null,
    /** Its active member's unread conversations, as last counted: the switcher's badge (Maurice). */
    val unread: Int? = null,
    /**
     * An invitation code received for this household and not yet used
     * (`Household.invite`): "Who's here?" offers it again until it has let
     * someone in or been refused, so that closing the enrollment loses
     * nothing. Kept on this device only.
     */
    val invite: String? = null,
) {
    val activeMember: Member? get() = members.firstOrNull { it.id == activeMemberId }
}
