package eu.chezmaurice.parity

import eu.chezmaurice.carnet.loadCarnetStrings
import eu.chezmaurice.carnet.shelf.deviceLink
import eu.chezmaurice.carnet.shelf.remaining
import eu.chezmaurice.core.settings.SettingsApi
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** « Connect a browser » (BrowserConnect.kt); `ShelfBrowserParity.swift`. */
class ShelfBrowserParity {
    @OptIn(ExperimentalTime::class)
    @Test fun browserConnect() = Parity.check("carnet", "shelf-browser-connect") { c ->
        when (c.string("call")) {
            "remaining" -> {
                val now = Instant.fromEpochSeconds(c.int("now")?.toLong() ?: 0L)
                mapOf("left" to remaining(c.string("expiresAt") ?: "", now, loadCarnetStrings("en")))
            }
            "state" -> {
                val api = SettingsApi(ShelfFixture.session().api()!!)
                val state = api.ownDeviceCodeState(c.string("code") ?: "")
                mapOf("requests" to ShelfObserve.requests(), "result" to state)
            }
            else -> {
                val session = ShelfFixture.session()
                ShelfFixture.exchange {
                    val code = SettingsApi(session.api()!!).ownDeviceCode()
                    mapOf("link" to deviceLink(Parity.baseUrl, code.code).replace(Parity.baseUrl, "{base}"), "expiresAt" to code.expiresAt)
                }
            }
        }
    }
}
