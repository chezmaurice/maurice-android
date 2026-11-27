package eu.chezmaurice.core.domains

import java.text.Collator

actual fun compareNames(a: String, b: String): Int {
    // A collator is not thread-safe: one per call, they are cheap.
    val c = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return c.compare(a, b)
}
