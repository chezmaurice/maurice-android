package eu.chezmaurice.parity

import eu.chezmaurice.core.model.DomainProposal
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.maurice.domains.fmtTok
import eu.chezmaurice.maurice.domains.proposalNumbers
import eu.chezmaurice.maurice.loadMauriceStrings
import kotlin.test.Test

class DomainsFormatParity {
    @Test fun domainsFormat() {
        val lang = System.getenv("PARITY_LANG") ?: "en"
        val strings = kotlinx.coroutines.runBlocking { loadMauriceStrings(lang) }
        Parity.check("maurice", "domains-format") { c ->
            c.int("fmtTok")?.let { return@check mapOf("text" to fmtTok(it)) }
            val p = runCatching { MauriceJson.decodeFromString(DomainProposal.serializer(), c.text("proposal")) }.getOrNull()
                ?: return@check mapOf("text" to "undecodable")
            mapOf("text" to proposalNumbers(p, strings, lang))
        }
    }
}
