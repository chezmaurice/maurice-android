package eu.chezmaurice.parity

import eu.chezmaurice.carnet.resources.Res
import eu.chezmaurice.ui.Strings
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.Test

/** Carnet's words: the table each language ships; `StringsParity.swift`. */
class StringsParity {
    @OptIn(ExperimentalResourceApi::class)
    @Test fun table() = Parity.check("carnet", "strings-table") { c ->
        // The copy of the iOS table the app reads (loadCarnetStrings). Its own
        // additions in files/android, and Maurice's table under it, are keys
        // this one is not compared on.
        Strings.parse(Res.readBytes("files/strings/${c.string}.strings").decodeToString())
    }
}
