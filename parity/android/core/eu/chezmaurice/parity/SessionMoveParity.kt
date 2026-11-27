package eu.chezmaurice.parity

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.session.HouseholdMove
import eu.chezmaurice.core.session.ed25519Verify
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class SessionMoveParity {
    @Test fun record() = Parity.check("maurice", "session-move-record") { c ->
        HouseholdMove.parse(c.string)
    }

    @Test fun bytes() = Parity.check("maurice", "session-move-bytes") { c ->
        c.string("txt")?.let { return@check mapOf("txt" to HouseholdMove.decodeTxt(SessionSupport.hex(it))) }
        mapOf("bytes" to HouseholdMove.base64Url(c.string("base64url").orEmpty())?.let(SessionSupport::hexString))
    }

    @Test fun ed25519() = Parity.check("maurice", "session-ed25519") { c ->
        val repeat = c.obj("repeat")
        val msg = when {
            c.string("text") != null -> c.string("text")!!.encodeToByteArray()
            repeat != null -> (repeat["text"] as JsonPrimitive).content.repeat((repeat["times"] as JsonPrimitive).intOrNull ?: 0).encodeToByteArray()
            else -> SessionSupport.hex(c.string("msg").orEmpty())
        }
        mapOf("valid" to ed25519Verify(SessionSupport.hex(c.string("pk").orEmpty()), msg, SessionSupport.hex(c.string("sig").orEmpty())))
    }

    private val http = ApiClient.defaultHttpClient()

    @Test fun proof() = Parity.check("maurice", "session-move-proof") { c ->
        val key = c.string("key").orEmpty()
        val result: Any? =
            if (c.bool("follow") == true) HouseholdMove.follow(Parity.baseUrl, key, http)
            else HouseholdMove.proves(Parity.baseUrl, key, http)
        mapOf(
            "result" to result,
            "requests" to SessionSupport.log().map { r ->
                mapOf(
                    "method" to r["method"], "path" to r["path"], "auth" to r["auth"],
                    "proofLength" to (r["query"]?.jsonObject?.get("proof") as? JsonPrimitive)?.content?.length,
                )
            },
        )
    }
}
