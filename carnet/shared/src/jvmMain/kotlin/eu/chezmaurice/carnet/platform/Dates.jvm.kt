package eu.chezmaurice.carnet.platform

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The JVM has no date skeletons: the language's medium date ("Mar 2, 2025",
 * "2 mars 2025"), its year taken out when not wanted.
 */
actual fun shortDate(year: Int, month: Int, day: Int, withYear: Boolean): String {
    val locale = Locale.getDefault()
    val medium = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM, null, IsoChronology.INSTANCE, locale)
    val pattern = if (withYear) medium else medium.replace(Regex("""[\s,.]*y+[.]?|y+[\s,.]*"""), "").trim()
    return DateTimeFormatter.ofPattern(pattern, locale).format(LocalDate.of(year, month, day))
}

/** The language's long date ("March 12, 2025", "12 mars 2025"), its year taken out when not wanted. */
actual fun longDate(year: Int, month: Int, day: Int, withYear: Boolean, language: String): String {
    val locale = Locale.forLanguageTag(language)
    val long = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.LONG, null, IsoChronology.INSTANCE, locale)
    // "d 'de' MMMM 'de' y" (es, pt) loses its last "de" with the year.
    val pattern = if (withYear) long else long.replace(Regex("""(\s*'de')?[\s,.]*y+[.]?|y+[\s,.]*"""), "").trim()
    return DateTimeFormatter.ofPattern(pattern, locale).format(LocalDate.of(year, month, day))
}
