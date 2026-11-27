package eu.chezmaurice.parity

import kotlin.test.Test

/** Talking an entry over with Maurice; `ShelfHandoffParity.swift`. */
class ShelfHandoffParity {
    @Test fun handoff() = Parity.check("carnet", "shelf-handoff") { c ->
        val garden = ShelfFixture.garden()
        ShelfFixture.exchange {
            // A refusal in the words the screen shows (handoffFailure), as iOS's MauriceAPI.message words it.
            try { mapOf("conversation" to garden.startConversation(c.string("entry") ?: "")) }
            catch (e: eu.chezmaurice.core.net.ApiError) {
                throw eu.chezmaurice.core.net.ApiError(e.status, eu.chezmaurice.carnet.entry.handoffFailure(e).orEmpty(), fromServer = true)
            }
        }
    }
}
