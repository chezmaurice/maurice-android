package eu.chezmaurice.maurice.platform

import java.text.DateFormat
import java.util.Date

actual fun longDateShortTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(epochMillis))

actual fun relativeWithinWeek(epochMillis: Long, nowMillis: Long): String? {
    // Never "in 0 seconds" for a clock a little ahead of the server's.
    val at = minOf(epochMillis, nowMillis - 1_000)
    return android.text.format.DateUtils.getRelativeTimeSpanString(at, nowMillis, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
}
