package eu.chezmaurice.carnet.entry

import eu.chezmaurice.carnet.platform.shortDate
import eu.chezmaurice.core.model.parseServerDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * When a conversation about an entry last moved (`EntryConversation.when`):
 * "Today", "Yesterday" (in English, as on iOS), else the day and the short
 * month in the device's language — "Mar 2" this year, "Mar 2, 2025" before.
 * Empty when the date does not read.
 */
@OptIn(ExperimentalTime::class)
internal fun whenOf(raw: String, now: Instant = Clock.System.now(), zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val day = parseServerDate(raw)?.toLocalDateTime(zone)?.date ?: return ""
    val today = now.toLocalDateTime(zone).date
    return when (day) {
        today -> "Today"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        else -> shortDate(day.year, day.month.ordinal + 1, day.day, withYear = day.year != today.year)
    }
}
