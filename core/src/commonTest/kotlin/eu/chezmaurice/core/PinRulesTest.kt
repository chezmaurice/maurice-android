package eu.chezmaurice.core

import eu.chezmaurice.core.session.PinRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinRulesTest {
    @Test fun theOnesEverybodyTriesAreRefused() {
        for (pin in listOf("1111", "1234", "9876", "1212", "123123", "2580", "000000", "456789")) assertTrue(PinRules.tooEasy(pin), pin)
    }

    @Test fun anOrdinaryPinPasses() {
        for (pin in listOf("4719", "8253", "730194", "1357")) assertFalse(PinRules.tooEasy(pin), pin)
    }
}
