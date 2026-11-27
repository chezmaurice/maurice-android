package eu.chezmaurice.ui

/**
 * `%.<precision>f` as Foundation prints it: the double's exact binary value,
 * rounded half to even only on an exact tie — 0.35 (really 0.34999…) gives
 * "0.3", 2.25 gives "2.2". Twin of `core/text/PrintfFixed.kt` (the ui module
 * cannot see core); keep them identical.
 */
internal fun printfFixed(value: Double, precision: Int = 6): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val bits = value.toRawBits()
    val negative = bits < 0
    val biased = ((bits ushr 52) and 0x7ff).toInt()
    var mantissa = bits and 0xfffffffffffffL
    val exponent = if (biased == 0) -1074 else { mantissa = mantissa or (1L shl 52); biased - 1075 }
    // value = mantissa · 2^exponent = digits / 10^scale, digits little-endian.
    val digits = ArrayList<Int>()
    var m = mantissa
    while (m > 0) { digits.add((m % 10).toInt()); m /= 10 }
    fun times(k: Int) {
        var carry = 0
        for (i in digits.indices) { val v = digits[i] * k + carry; digits[i] = v % 10; carry = v / 10 }
        while (carry > 0) { digits.add(carry % 10); carry /= 10 }
    }
    val scale: Int
    if (exponent >= 0) { repeat(exponent) { times(2) }; scale = 0 } else { repeat(-exponent) { times(5) }; scale = -exponent }
    val kept: MutableList<Int>
    if (scale <= precision) {
        kept = MutableList(precision - scale) { 0 }.apply { addAll(digits) }
    } else {
        val drop = scale - precision
        kept = if (drop < digits.size) digits.subList(drop, digits.size).toMutableList() else mutableListOf()
        val first = digits.getOrElse(drop - 1) { 0 }
        val restNonZero = (0 until minOf(drop - 1, digits.size)).any { digits[it] != 0 }
        val odd = (kept.firstOrNull() ?: 0) % 2 == 1
        if (first > 5 || (first == 5 && (restNonZero || odd))) {
            var i = 0
            while (true) {
                if (i == kept.size) { kept.add(1); break }
                if (kept[i] == 9) { kept[i] = 0; i++ } else { kept[i]++; break }
            }
        }
    }
    while (kept.size < precision + 1) kept.add(0)
    while (kept.size > precision + 1 && kept.last() == 0) kept.removeAt(kept.size - 1)
    val text = kept.asReversed().joinToString("")
    val number = if (precision == 0) text else text.dropLast(precision) + "." + text.takeLast(precision)
    return if (negative) "-$number" else number
}
