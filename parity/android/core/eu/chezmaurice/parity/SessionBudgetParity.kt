package eu.chezmaurice.parity

import eu.chezmaurice.core.budget.BudgetStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class SessionBudgetParity {
    @Test fun budget() = Parity.check("maurice", "session-budget") { c ->
        val session = MauriceFixture.session()
        // The key the home screen gives it (HomeScreen.kt): "<server>|<member>".
        val store = BudgetStore(session.api()!!, session.store, "${Parity.baseUrl}|u1")
        c.array("steps").orEmpty().map { step ->
            Parity.reset(JsonArray(listOf(route(step))))
            store.refresh()
            val s = store.state.value
            mapOf("left" to s.left, "isLimited" to s.isLimited, "alert" to s.alert)
        }
    }

    /** One step: a budget as JSON, `{"status": n}` a failure, `{"text": …}` a raw body. */
    private fun route(step: JsonElement): JsonObject {
        val d = step as? JsonObject ?: JsonObject(emptyMap())
        val r = linkedMapOf<String, JsonElement>("method" to JsonPrimitive("GET"), "path" to JsonPrimitive("/api/me/budget"))
        when {
            d["status"] != null -> { r["status"] = d["status"]!!; r["json"] = JsonObject(mapOf("error" to JsonPrimitive("x"))) }
            d["text"] != null -> r["text"] = d["text"]!!
            else -> r["json"] = d
        }
        return JsonObject(r)
    }
}
