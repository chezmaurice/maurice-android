package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.session.Invitation

/**
 * Carnet's way in, read as Carnet on iOS reads it (`Invitation.swift` —
 * `Invitation`, `EntryInput`): one field takes an invitation link, a code
 * alone or a household's address. Stricter than Maurice's: a code is eight
 * letters and digits with at most one dash; an address is a host (a dot in
 * it, or `localhost`) and its port, nothing else — no path, query or user.
 */
object CarnetEntry {
    /**
     * `https://<household>/join?code=<CODE>` (the QR code, the shared link),
     * or `carnet://join?server=<origin>&code=<CODE>` (`maurice://` alike).
     * Its code is kept as letters and digits, as the server reads it.
     */
    fun invitation(raw: String): Invitation? {
        val url = Link.parse(raw.trim()) ?: return null
        val code = url.query("code")?.let(::code) ?: return null
        val plain = code.filter { it != '-' }
        return when (url.scheme.lowercase()) {
            "carnet", "maurice" -> {
                if (url.host != "join") return null
                val server = url.query("server")?.let(::address) ?: return null
                Invitation(server, plain)
            }
            "https", "http" -> {
                if (url.path != "/join" || url.host.isNullOrEmpty()) return null
                val port = url.port?.let { ":$it" } ?: ""
                Invitation("${url.scheme.lowercase()}://${url.host}$port", plain)
            }
            else -> null
        }
    }

    /** `XXXXXXXX` or `XXXX-XXXX`, any case → `XXXX-XXXX`; null for anything else. */
    fun code(raw: String): String? {
        val letters = raw.uppercase().filter { it.isLetterOrDigit() }
        val typed = raw.trim()
        if (letters.length != 8) return null
        if (!typed.all { it.isLetterOrDigit() || it == '-' } || typed.count { it == '-' } > 1) return null
        return letters.take(4) + "-" + letters.takeLast(4)
    }

    /**
     * `monfoyer.chezmaurice.eu`, with or without scheme or trailing path →
     * `https://monfoyer.chezmaurice.eu`. A typed `http://` is kept (a home
     * server on the local network). Null when there is no host to speak of.
     */
    fun address(raw: String): String? {
        var typed = raw.trim()
        if (typed.isEmpty() || ' ' in typed) return null
        if ("://" !in typed) typed = "https://$typed"
        val url = Link.parse(typed) ?: return null
        val scheme = url.scheme.lowercase()
        val host = url.host ?: return null
        if (scheme != "https" && scheme != "http") return null
        if ('.' !in host && host != "localhost") return null
        return "$scheme://${host.lowercase()}${url.port?.let { ":$it" } ?: ""}"
    }

    /**
     * A URL split as `URLComponents` splits it: what does not parse there
     * (a space, a quote, letters for a port) is null here too.
     */
    private class Link(val scheme: String, val host: String?, val port: String?, val path: String, val rawQuery: String?) {
        /** The first item of that name, percent-decoded (`+` stays a plus). */
        fun query(name: String): String? = rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == name }
            ?.let { if ('=' in it) decode(it.substringAfter('=')) else null }

        companion object {
            private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
            private const val REFUSED = " \t\r\n\"<>\\^`{|}"

            fun parse(text: String): Link? {
                if (text.isEmpty() || text.any { it in REFUSED || it.isISOControl() }) return null
                val colon = text.indexOf(':')
                if (colon <= 0) return null
                val scheme = text.substring(0, colon)
                if (!SCHEME.matches(scheme)) return null
                var rest = text.substring(colon + 1).substringBefore('#')
                val rawQuery = if ('?' in rest) rest.substringAfter('?') else null
                rest = rest.substringBefore('?')
                if (!rest.startsWith("//")) return Link(scheme, null, null, rest, rawQuery)
                rest = rest.drop(2)
                val authority = rest.substringBefore('/')
                val path = rest.drop(authority.length)
                val hostPort = authority.substringAfterLast('@')
                val host: String
                val port: String?
                if (hostPort.startsWith("[")) {
                    val end = hostPort.indexOf(']')
                    if (end < 0) return null
                    host = hostPort.substring(0, end + 1)
                    port = hostPort.substring(end + 1).let { if (it.isEmpty()) null else if (it.startsWith(":")) it.drop(1) else return null }
                } else {
                    host = hostPort.substringBefore(':')
                    port = if (':' in hostPort) hostPort.substringAfter(':') else null
                }
                if (port != null && port.isNotEmpty() && !port.all { it in '0'..'9' }) return null
                return Link(scheme, host, port?.takeIf { it.isNotEmpty() }?.trimStart('0')?.ifEmpty { "0" }, path, rawQuery)
            }

            private fun decode(s: String): String? {
                if ('%' !in s) return s
                val bytes = ArrayList<Byte>()
                var i = 0
                while (i < s.length) {
                    val ch = s[i]
                    if (ch == '%') {
                        if (i + 3 > s.length) return null
                        val b = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                        bytes += b.toByte(); i += 3
                    } else {
                        ch.toString().encodeToByteArray().forEach { bytes += it }; i++
                    }
                }
                return bytes.toByteArray().decodeToString()
            }
        }
    }
}
