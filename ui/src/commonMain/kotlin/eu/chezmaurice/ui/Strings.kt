package eu.chezmaurice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The iOS apps' `Localizable.strings`, read as they are: same keys, same
 * placeholders (`%@`, `%d`, `%1$@`…), so a ported view says exactly what the
 * Swift one says. `scripts/sync-strings.sh` copies them over.
 */
class Strings(private val table: Map<String, String>, private val fallback: Map<String, String> = emptyMap()) {

    operator fun invoke(key: String, vararg args: Any?): String {
        val template = table[key] ?: fallback[key] ?: return key
        return if (args.isEmpty()) template else format(template, args)
    }

    fun has(key: String) = key in table || key in fallback

    companion object {
        val EMPTY = Strings(emptyMap())

        /** Parse the `"key" = "value";` format, with its comments and escapes. */
        fun parse(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var i = 0
            fun skip() {
                while (i < text.length) {
                    when {
                        text[i].isWhitespace() -> i++
                        text.startsWith("//", i) -> { while (i < text.length && text[i] != '\n') i++ }
                        text.startsWith("/*", i) -> { val end = text.indexOf("*/", i + 2); i = if (end < 0) text.length else end + 2 }
                        else -> return
                    }
                }
            }
            fun quoted(): String? {
                if (i >= text.length || text[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < text.length && text[i] != '"') {
                    val c = text[i++]
                    if (c == '\\' && i < text.length) {
                        when (val e = text[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'U', 'u' -> {
                                val hex = text.substring(i, minOf(i + 4, text.length)); i += hex.length
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            }
                            else -> sb.append(e)
                        }
                    } else sb.append(c)
                }
                i++ // closing quote
                return sb.toString()
            }
            while (true) {
                skip()
                val key = quoted() ?: break
                skip(); if (i < text.length && text[i] == '=') i++; skip()
                val value = quoted() ?: break
                skip(); if (i < text.length && text[i] == ';') i++
                out[key] = value
            }
            return out
        }

        /** `%@ %d %ld %f %.1f %%` and their positional forms `%1$@`, as Foundation reads them. */
        fun format(template: String, args: Array<out Any?>): String {
            val sb = StringBuilder()
            var next = 0
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c != '%' || i + 1 >= template.length) { sb.append(c); i++; continue }
                if (template[i + 1] == '%') { sb.append('%'); i += 2; continue }
                var j = i + 1
                var position: Int? = null
                val digits = StringBuilder()
                while (j < template.length && template[j].isDigit()) digits.append(template[j++])
                if (j < template.length && template[j] == '$' && digits.isNotEmpty()) { position = digits.toString().toInt() - 1; j++; digits.clear() }
                // Width such as %02d.
                val width = digits.toString()
                // Precision such as %.1f.
                var precision: Int? = null
                if (j < template.length && template[j] == '.') {
                    j++
                    val p = StringBuilder()
                    while (j < template.length && template[j].isDigit()) p.append(template[j++])
                    precision = p.toString().toIntOrNull() ?: 0
                }
                while (j < template.length && template[j] == 'l') j++
                if (j >= template.length) { sb.append(template.substring(i)); break }
                val conversion = template[j]
                val arg = args.getOrNull(position ?: next++)
                val text = when (conversion) {
                    'd', 'i', 'u' -> (arg as? Number)?.toLong()?.toString() ?: arg.toString()
                    'f' -> (arg as? Number)?.let { printfFixed(it.toDouble(), precision ?: 6) } ?: arg.toString()
                    else -> arg?.toString() ?: ""
                }
                sb.append(if (width.startsWith("0") && width.length > 1) text.padStart(width.drop(1).toInt(), '0') else text)
                i = j + 1
            }
            return sb.toString()
        }
    }
}

val LocalStrings = staticCompositionLocalOf { Strings.EMPTY }

/** `L("chat.stop")` — the same call as in the Swift views. */
@Composable
fun L(key: String, vararg args: Any?): String = LocalStrings.current(key, *args)
