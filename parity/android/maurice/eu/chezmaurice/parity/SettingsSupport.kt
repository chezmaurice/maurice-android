package eu.chezmaurice.parity

import eu.chezmaurice.maurice.loadMauriceStrings
import eu.chezmaurice.ui.Strings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext

/**
 * Helpers of the Settings lot (SettingsReportsParity, SettingsMailParity,
 * SettingsDataParity, SettingsDictationParity, SettingsStoreParity).
 */
object SettingsSupport {
    /** The household's log, only the requests whose path starts with one of [prefixes]. */
    suspend fun requests(vararg prefixes: String): List<JsonElement> =
        (Parity.requests() as JsonArray).filter { r ->
            val path = r.jsonObject["path"]?.jsonPrimitive?.content ?: ""
            prefixes.any { path.startsWith(it) }
        }

    /** The app's words, as the app loads them (iOS table, then the Android one over it). */
    val strings: Strings by lazy { runBlocking { loadMauriceStrings(System.getenv("PARITY_LANG") ?: "en") } }

    /**
     * A file-private value of the app's (a `private val` at the top of a file),
     * read as it is: these tables are what the screens show and nothing else
     * reaches them.
     */
    fun privateTopLevel(fileClass: String, name: String): Any? =
        Class.forName(fileClass).getDeclaredField(name).apply { isAccessible = true }.get(null)

    /**
     * A scope whose coroutines never run: a controller's own background loads
     * (opening a conversation) stay out of a case that only drives one call.
     */
    val inert: CoroutineScope = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    })
}
