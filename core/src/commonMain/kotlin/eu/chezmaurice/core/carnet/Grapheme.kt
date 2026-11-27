package eu.chezmaurice.core.carnet

/**
 * The first character of [text] as Swift counts one (a grapheme cluster):
 * a code point, then what joins it — combining marks, variation selectors,
 * skin tones, a keycap, tag characters, a zero-width joiner and what it
 * joins, the second half of a flag. Never half of a surrogate pair.
 */
internal fun firstGrapheme(text: String): String {
    if (text.isEmpty()) return ""
    fun codePointAt(i: Int): Int =
        if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate())
            0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
        else text[i].code
    fun width(cp: Int) = if (cp >= 0x10000) 2 else 1
    fun extends(cp: Int): Boolean =
        cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F ||
            (cp < 0x10000 && cp.toChar().category.let {
                it == CharCategory.NON_SPACING_MARK || it == CharCategory.ENCLOSING_MARK || it == CharCategory.COMBINING_SPACING_MARK
            })
    val first = codePointAt(0)
    var i = width(first)
    if (first in 0x1F1E6..0x1F1FF && i < text.length && codePointAt(i) in 0x1F1E6..0x1F1FF) i += 2
    while (i < text.length) {
        val cp = codePointAt(i)
        if (!extends(cp)) break
        i += width(cp)
        // A joiner takes the code point after it into the cluster.
        if (cp == 0x200D && i < text.length) i += width(codePointAt(i))
    }
    return text.substring(0, i)
}
