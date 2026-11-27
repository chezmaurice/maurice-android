package eu.chezmaurice.maurice.platform

/**
 * A moment as the system writes it, long date and short time, in the device's
 * language and time zone: "November 2, 2026 at 3:32 PM" — iOS's
 * `formatted(date: .long, time: .shortened)`.
 */
expect fun longDateShortTime(epochMillis: Long): String

/**
 * "3 days ago", in the device's language, for a moment within the last week
 * (iOS's `RelativeDateTimeFormatter`, full style); null where the platform has
 * no such words (the desktop), the caller then writes a date.
 */
expect fun relativeWithinWeek(epochMillis: Long, nowMillis: Long): String?
