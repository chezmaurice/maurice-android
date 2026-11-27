package eu.chezmaurice.core

import eu.chezmaurice.core.text.printfFixed
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PrintfFixedTest {
    @Test fun asFoundationPrintsThem() {
        assertEquals("0.3", printfFixed(0.35, 1))
        assertEquals("0.2", printfFixed(0.25, 1))
        assertEquals("2.2", printfFixed(2.25, 1))
        assertEquals("2.4", printfFixed(2.35, 1))
        assertEquals("60.0", printfFixed(59.95, 1))
        assertEquals("600.0", printfFixed(600.0, 1))
        assertEquals("1234.6", printfFixed(1234.56, 1))
        assertEquals("1.500000", printfFixed(1.5))
        assertEquals("-0.0", printfFixed(-0.04, 1))
        assertEquals("0", printfFixed(0.5, 0))
        assertEquals("2", printfFixed(1.5, 0))
        assertEquals("0.00", printfFixed(0.0, 2))
        assertEquals("100.00", printfFixed(99.999, 2))
    }

    /** The exact binary value, half to even: what BigDecimal(double) says too. */
    @Test fun agreesWithTheExactValue() {
        val random = Random(42)
        repeat(5_000) {
            val v = when (it % 3) {
                0 -> random.nextDouble(0.0, 10.0)
                1 -> random.nextInt(0, 100_000) / 100.0 + 0.005
                else -> random.nextDouble(-1e6, 1e6)
            }
            for (p in 0..6) {
                val expected = BigDecimal(v).setScale(p, RoundingMode.HALF_EVEN).toPlainString()
                val got = printfFixed(v, p)
                // printf keeps the sign of a negative that rounds to zero ("-0.0"); BigDecimal does not.
                assertEquals(expected, if (got.startsWith("-") && !expected.startsWith("-")) got.drop(1) else got, "$v %.${p}f")
            }
        }
    }
}
