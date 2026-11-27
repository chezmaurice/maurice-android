package eu.chezmaurice.parity

import eu.chezmaurice.core.session.PinRules
import kotlin.test.Test

class PinRulesParity {
    @Test fun pinRules() = Parity.check("maurice", "pin-rules") { c ->
        mapOf("tooEasy" to PinRules.tooEasy(c.string))
    }
}
