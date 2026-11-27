package eu.chezmaurice.carnet.platform

/**
 * A day with its abbreviated month, and the year if asked, in the device's
 * language — what Foundation writes for `.dateTime.day().month(.abbreviated)`
 * (`.year()`): "Mar 2" / "Mar 2, 2025" in English, "2 mars 2025" in French.
 */
expect fun shortDate(year: Int, month: Int, day: Int, withYear: Boolean): String

/**
 * A day with its month written out, and the year if asked, in the app's
 * [language] — Foundation's `dMMMM` / `dMMMMyyyy` templates: "March 12" /
 * "March 12, 2025" in English, "12 mars" / "12 mars 2025" in French.
 */
expect fun longDate(year: Int, month: Int, day: Int, withYear: Boolean, language: String): String
