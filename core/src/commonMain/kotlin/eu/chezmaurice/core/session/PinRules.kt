package eu.chezmaurice.core.session

/**
 * The PINs everybody tries first — the server refuses them too
 * (`server/src/services/credentials.ts`, pinProblem); said here first, in the
 * member's language (`PinRules` in `UserPickerView.swift`).
 */
object PinRules {
    private val common = setOf(
        "1004", "2000", "6969", "1122", "1313", "2001", "1010", "4321", "1984", "1999", "2580", "0852",
        "121212", "112233", "123321", "159753", "696969", "000000", "123123", "654321", "111222",
    )

    fun tooEasy(pin: String): Boolean {
        if (pin.length < 4) return false
        // A digit of any script counts by its value, as iOS's wholeNumberValue does: ١٢٣٤ is 1234.
        val d = pin.map { it.digitToIntOrNull() ?: return false }
        if (d.toSet().size == 1) return true                                                  // 1111
        val step = d[1] - d[0]
        if ((step == 1 || step == -1) && d.zipWithNext().all { (a, b) -> b - a == step }) return true // 1234, 9876
        if (pin.length % 2 == 0 && pin.take(2).repeat(pin.length / 2) == pin) return true      // 1212
        if (pin.length == 6 && pin.take(3) == pin.takeLast(3)) return true                     // 123123
        return pin in common
    }
}
