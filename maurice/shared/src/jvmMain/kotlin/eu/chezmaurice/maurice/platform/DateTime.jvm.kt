package eu.chezmaurice.maurice.platform

import java.text.DateFormat
import java.util.Date

actual fun longDateShortTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(epochMillis))

/** The JVM has no relative wording without ICU: the caller writes the date. */
actual fun relativeWithinWeek(epochMillis: Long, nowMillis: Long): String? = null
