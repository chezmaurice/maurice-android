package eu.chezmaurice.core.session

import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent

/**
 * A server address plus a code. It reaches the app as the QR link
 * `https://<host>/join?code=…` or as `maurice://join?server=…&code=…`
 * (`carnet://join?…` for Carnet): the same invitation for either app.
 */
data class Invitation(val serverUrl: String, val code: String) {
    val link: String get() = "$serverUrl/join?code=$code"

    companion object {
        fun parse(raw: String): Invitation? {
            val text = raw.trim()
            val url = runCatching { Url(text) }.getOrNull() ?: return null
            // XXXX-XXXX or XXXXXXXX, either case: letters and digits only.
            val code = url.parameters["code"].orEmpty().uppercase().filter { it.isLetterOrDigit() }
            if (code.isEmpty()) return null
            return when {
                (url.protocol.name == "maurice" || url.protocol.name == "carnet") && url.host == "join" -> {
                    val server = url.parameters["server"]?.decodeURLQueryComponent() ?: return null
                    normalizeServerUrl(server)?.let { Invitation(it, code) }
                }
                (url.protocol.name == "https" || url.protocol.name == "http") &&
                    url.encodedPath.trimEnd('/') == "/join" -> {
                    val port = if (url.specifiedPort != 0 && url.specifiedPort != url.protocol.defaultPort) ":${url.specifiedPort}" else ""
                    Invitation("${url.protocol.name}://${url.host}$port", code)
                }
                else -> null
            }
        }
    }
}

/**
 * What the member typed, made into a base URL: trimmed, `https://` unless a
 * scheme was given, no trailing slash, a host required.
 */
fun normalizeServerUrl(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
    text = text.trimEnd('/')
    val host = runCatching { Url(text).host }.getOrNull()
    return if (host.isNullOrBlank()) null else text
}

/** The same code, however it was typed: dashes, spaces and case aside (`Invitation.same`). */
fun sameCode(a: String, b: String?): Boolean {
    if (b == null) return false
    val bare = { s: String -> s.uppercase().filter { it.isLetterOrDigit() } }
    return bare(a).isNotEmpty() && bare(a) == bare(b)
}

/** Invitation codes are shown `XXXX-XXXX`; the server ignores everything but letters and digits. */
fun formatInviteCode(code: String): String {
    val clean = code.uppercase().filter { it.isLetterOrDigit() }
    return if (clean.length == 8) clean.take(4) + "-" + clean.drop(4) else clean
}

/**
 * A code as it is being typed, shown `XXXX-XXXX` as it grows: the dash
 * appears with the fifth character, or as soon as the member types it
 * after the fourth (a dash typed is no longer swallowed), and a backspace
 * over it removes it. At most eight letters or digits.
 */
fun typedInviteCode(text: String): String {
    val clean = text.uppercase().filter { it.isLetterOrDigit() }.take(8)
    val dashTyped = clean.length == 4 && text.trimEnd().endsWith("-")
    return if (clean.length > 4 || dashTyped) clean.take(4) + "-" + clean.drop(4) else clean
}
