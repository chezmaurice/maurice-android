package eu.chezmaurice.core.chat

/**
 * Characters as the member sees them — what Swift's `String.count` counts:
 * an emoji (flags, families, skin tones), a letter and its accents, a CR LF,
 * each one. The common cases of Unicode's grapheme clusters, without ICU
 * (common code has none): combining marks, joiners, variation selectors,
 * emoji modifiers and tags, flag pairs.
 */
object Graphemes {
    fun count(text: String): Int = split(text).size

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val cluster = StringBuilder()
        var prev = -1
        var flags = 0
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            val width = if (cp > 0xFFFF) 2 else 1
            val joins = prev >= 0 && (
                (prev == 0x0D && cp == 0x0A) ||
                    (prev != 0x0D && prev != 0x0A && extends(cp)) ||
                    prev == ZWJ ||
                    (isFlag(cp) && flags % 2 == 1)
                )
            if (!joins && cluster.isNotEmpty()) {
                out += cluster.toString()
                cluster.clear()
                flags = 0
            }
            cluster.append(text, i, i + width)
            if (isFlag(cp)) flags++ else flags = 0
            prev = cp
            i += width
        }
        if (cluster.isNotEmpty()) out += cluster.toString()
        return out
    }

    private const val ZWJ = 0x200D

    private fun isFlag(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun extends(cp: Int): Boolean = when {
        cp == ZWJ || cp == 0x200C -> true
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF -> true // variation selectors
        cp in 0x1F3FB..0x1F3FF -> true // skin tones
        cp in 0xE0020..0xE007F -> true // tags (subdivision flags)
        cp <= 0xFFFF -> cp.toChar().category.let {
            it == CharCategory.NON_SPACING_MARK || it == CharCategory.ENCLOSING_MARK || it == CharCategory.COMBINING_SPACING_MARK
        }
        else -> false
    }

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return c.code
    }
}
