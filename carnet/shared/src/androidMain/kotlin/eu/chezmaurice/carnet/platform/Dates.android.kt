package eu.chezmaurice.carnet.platform

import android.icu.text.DateFormat
import android.icu.util.GregorianCalendar
import java.util.Locale

/** ICU's skeletons, as Foundation's date styles use them: "dMMM" or "dMMMy". */
actual fun shortDate(year: Int, month: Int, day: Int, withYear: Boolean): String {
    val format = DateFormat.getInstanceForSkeleton(if (withYear) "dMMMy" else "dMMM", Locale.getDefault())
    return format.format(GregorianCalendar(year, month - 1, day).time)
}

/** ICU's skeletons, as Foundation's templates use them: "dMMMM" or "dMMMMy", in the app's [language]. */
actual fun longDate(year: Int, month: Int, day: Int, withYear: Boolean, language: String): String {
    val format = DateFormat.getInstanceForSkeleton(if (withYear) "dMMMMy" else "dMMMM", Locale.forLanguageTag(language))
    return format.format(GregorianCalendar(year, month - 1, day).time)
}
