package eu.chezmaurice.parity

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.loadMauriceStrings
import eu.chezmaurice.maurice.settings.accountEventSentence
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

class SessionEventsParity {
    @Test fun accountEvents() = Parity.check("maurice", "session-account-events") { _ ->
        val session = MauriceFixture.session()
        val strings = loadMauriceStrings("en")
        val events = SettingsApi(session.api()!!).accountEvents()
        val requests = ((Parity.requests() as? JsonArray) ?: JsonArray(emptyList())).map { it.jsonObject }
            .filter { (it["path"] as? JsonPrimitive)?.content != "/api/me/ws" }.map { JsonObject(it - "query") }
        mapOf(
            "requests" to requests,
            "events" to events?.map { e ->
                mapOf(
                    "id" to e.id, "kind" to e.kind, "unseen" to (e.seenAt == null),
                    "sentence" to SessionEventsSupport.composeOnce(strings) { accountEventSentence(e) },
                )
            },
        )
    }
}

/** Runs a composable that only returns a value (no UI) once, in [strings]. */
object SessionEventsSupport {
    private object NoApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    fun <T> composeOnce(strings: Strings, content: @Composable () -> T): T {
        var out: Any? = null
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(NoApplier, recomposer)
        try {
            composition.setContent { CompositionLocalProvider(LocalStrings provides strings) { out = content() } }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }
}
