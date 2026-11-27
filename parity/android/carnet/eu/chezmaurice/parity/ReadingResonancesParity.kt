package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.LinkTarget
import eu.chezmaurice.core.carnet.ResonanceSource
import eu.chezmaurice.core.carnet.ResonancesApi
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class ReadingResonancesParity {
    @Test fun readingResonances() = Parity.check("carnet", "reading-resonances") { c ->
        val api = ResonancesApi(ReadingFixture.api())
        ReadingObs.exchange {
            when (c.string("op")) {
                "targets" -> api.targets(c.string("q") ?: "").map(ReadingObs::target)
                "create" -> {
                    val to = MauriceJson.decodeFromString(LinkTarget.serializer(), c.text("to"))
                    val s = c.obj("source")
                    val source = s?.let { ResonanceSource(it["label"]?.jsonPrimitive?.content ?: "", it["basename"]?.jsonPrimitive?.content, it["bookTitle"]?.jsonPrimitive?.content) }
                    api.create(to, c.string("comment") ?: "", c.string("quote"), source)
                    "ok"
                }
                else -> mapOf("unknown op" to c.string("op"))
            }
        }
    }
}
