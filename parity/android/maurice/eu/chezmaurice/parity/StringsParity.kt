package eu.chezmaurice.parity

import eu.chezmaurice.maurice.resources.Res
import eu.chezmaurice.ui.Strings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.Test

/** The app's words: the table each language ships, and how a template with placeholders is filled. */
class StringsParity {
    @OptIn(ExperimentalResourceApi::class)
    @Test fun table() = Parity.check("maurice", "strings-table") { c ->
        // The copy of the iOS table the app reads (loadMauriceStrings); its own
        // additions in files/android are keys iOS does not have.
        Strings.parse(Res.readBytes("files/strings/${c.string}.strings").decodeToString())
    }

    @Test fun format() = Parity.check("maurice", "strings-format") { c ->
        val args = c.array("args").orEmpty().map<kotlinx.serialization.json.JsonElement, Any?> { a ->
            val p = a as JsonPrimitive
            when {
                p.isString -> p.content
                p.longOrNull != null -> p.longOrNull!!.toInt()
                else -> p.doubleOrNull
            }
        }
        Strings(mapOf("k" to c.string("template")!!))("k", *args.toTypedArray())
    }
}
