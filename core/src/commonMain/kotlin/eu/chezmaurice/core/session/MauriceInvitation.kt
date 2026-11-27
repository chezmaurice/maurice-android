package eu.chezmaurice.core.session

import io.ktor.http.Url

/**
 * Maurice's own reading of invitations and typed addresses, as the iOS app
 * reads them (`Invitation` in `SessionStore.swift`, `PairingView.normalizedURL`).
 * Carnet reads them its own way ([Invitation.parse], [normalizeServerUrl]):
 * the two iOS apps differ, and each Android app follows its own.
 *
 * The code is kept as the link gives it: the server normalises it.
 */
object MauriceInvitation {
    /** `https://<household>/join?code=…` or `maurice://join?server=…&code=…`; anything else is not one. */
    fun parse(raw: String): Invitation? {
        val text = raw.trim()
        val url = runCatching { Url(text) }.getOrNull() ?: return null
        val code = url.parameters["code"]?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = text.substringBefore(':', "").lowercase()
        return when (scheme) {
            "maurice" -> {
                if (authority(text) != "join") return null
                val server = url.parameters["server"] ?: return null
                address(server)?.let { Invitation(it, code) }
            }
            "https", "http" -> {
                if (url.encodedPath != "/join") return null
                val host = hostOf(text)?.takeIf { it.isNotEmpty() } ?: return null
                val port = if (url.specifiedPort != 0) ":${url.specifiedPort}" else ""
                Invitation("$scheme://$host$port", code)
            }
            else -> null
        }
    }

    /**
     * What the member typed, made into a base URL: trimmed, `https://` unless
     * a scheme was given (in any case), no trailing slash, a host required.
     * The rest is kept as typed.
     */
    fun address(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) text = "https://$text"
        text = text.trimEnd('/')
        return if (hostOf(text).isNullOrEmpty()) null else text
    }

    /**
     * The link an invitation's QR code carries: the household's landing page,
     * the code percent-encoded as a query value, a Unicode host in its
     * ASCII (punycode) form.
     */
    fun link(server: String, code: String): String {
        val host = hostOf(server)
        val base = if (host != null && host.any { it.code > 0x7f }) server.replaceFirst(host, asciiHost(host)) else server
        return "$base/join?code=" + queryValue(code)
    }

    /** The authority of `scheme://authority/…`, or null without `//`. */
    private fun authority(text: String): String? {
        val colon = text.indexOf(':')
        if (colon < 0 || !text.startsWith("//", colon + 1)) return null
        val rest = text.substring(colon + 3)
        return rest.substring(0, rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it })
    }

    /**
     * The host of an absolute URL, or null when it has none or cannot be one:
     * no `//`, whitespace in it, a port that is not digits.
     */
    internal fun hostOf(text: String): String? {
        val authority = authority(text) ?: return null
        val hostPort = authority.substringAfterLast('@')
        val host: String
        val port: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(0, close + 1)
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return null
            port = after.removePrefix(":")
        } else {
            host = hostPort.substringBefore(':')
            port = hostPort.substringAfter(':', "")
        }
        if (!port.all { it in '0'..'9' }) return null
        if (host.any { it.isWhitespace() || it.isISOControl() }) return null
        return host
    }

    /** As URLComponents encodes a query item's value: `&`, `=`, `#`, spaces and non-ASCII escaped. */
    private fun queryValue(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = (b.toInt() and 0xff)
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~!$'()*+,;:@/?")) append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    private const val HEX = "0123456789ABCDEF"

    private fun asciiHost(host: String): String =
        host.split('.').joinToString(".") { label ->
            if (label.all { it.code < 0x80 }) label else "xn--" + punycode(label.lowercase())
        }

    /** RFC 3492. */
    private fun punycode(input: String): String {
        val points = buildList { var i = 0; while (i < input.length) { val cp = codePointAt(input, i); add(cp); i += if (cp > 0xffff) 2 else 1 } }
        val out = StringBuilder()
        points.filter { it < 0x80 }.forEach { out.append(it.toChar()) }
        val basic = out.length
        var handled = basic
        if (basic > 0) out.append('-')
        var n = 128; var delta = 0; var bias = 72
        while (handled < points.size) {
            val m = points.filter { it >= n }.min()
            delta += (m - n) * (handled + 1)
            n = m
            for (c in points) {
                if (c < n) delta++
                if (c == n) {
                    var q = delta; var k = 36
                    while (true) {
                        val t = if (k <= bias) 1 else if (k >= bias + 26) 26 else k - bias
                        if (q < t) break
                        out.append(digit(t + (q - t) % (36 - t)))
                        q = (q - t) / (36 - t)
                        k += 36
                    }
                    out.append(digit(q))
                    bias = adapt(delta, handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++; n++
        }
        return out.toString()
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun adapt(delta0: Int, points: Int, first: Boolean): Int {
        var delta = if (first) delta0 / 700 else delta0 / 2
        delta += delta / points
        var k = 0
        while (delta > 455) { delta /= 35; k += 36 }
        return k + 36 * delta / (delta + 38)
    }

    private fun codePointAt(s: String, i: Int): Int {
        val hi = s[i]
        if (hi.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate())
            return ((hi.code - 0xd800) shl 10) + (s[i + 1].code - 0xdc00) + 0x10000
        return hi.code
    }
}
