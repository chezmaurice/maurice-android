package eu.chezmaurice.core.model

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The server's dates, read leniently: naive `YYYY-MM-DD HH:MM:SS` (SQLite,
 * UTC — never local), a bare date, ISO with `Z`, with milliseconds, with an
 * offset and microseconds. Null when it is none of these.
 */
@OptIn(ExperimentalTime::class)
fun parseServerDate(raw: String?): Instant? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val iso = when {
        text.length == 10 -> text + "T00:00:00Z"
        text.length == 19 && (text[10] == ' ' || text[10] == 'T') -> text.replace(' ', 'T') + "Z"
        else -> text.replace(' ', 'T')
    }
    return runCatching { Instant.parse(iso) }.getOrNull()
}

/** `21/10/2026 20:00`, in the device's time zone. */
@OptIn(ExperimentalTime::class)
fun formatServerDate(raw: String?, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
    val local = parseServerDate(raw)?.toLocalDateTime(zone) ?: return null
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(local.day)}/${two(local.month.ordinal + 1)}/${local.year} ${two(local.hour)}:${two(local.minute)}"
}
