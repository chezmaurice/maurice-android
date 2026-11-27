package eu.chezmaurice.parity

import eu.chezmaurice.core.session.DeviceInfo
import eu.chezmaurice.core.session.Prefs
import eu.chezmaurice.core.session.Session
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class SessionPrefsParity {
    /**
     * The device report's values are the platform's own: only its shape is
     * compared — the fields that carry a value. Not `app_version`: the iOS
     * parity runner has no bundle version (SessionPrefsParity.swift).
     */
    private fun summarise(log: List<JsonObject>) = log.map { r ->
        val out = linkedMapOf<String, Any?>("method" to r["method"], "path" to r["path"], "auth" to r["auth"], "contentType" to r["contentType"])
        val body = r["body"]
        if ((r["path"] as? JsonPrimitive)?.content == "/api/users/me/device" && body is JsonObject) {
            out["body"] = mapOf(
                "fields" to body.filter { it.value !is JsonNull && it.key != "app_version" }.keys.sorted(), "app" to body["app"],
                "installIdLength" to (body["install_id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.length,
            )
        } else out["body"] = body
        r["unmatched"]?.let { out["unmatched"] = it }
        out
    }

    @Test fun prefs() = Parity.check("maurice", "session-prefs") { c ->
        val session = Session(MauriceFixture.store(), "maurice",
            device = DeviceInfo(platform = "Android", model = "Pixel 8", osVersion = "15", app = "maurice", appVersion = "1.0 (1)"))
        session.loadServerPrefs("en")
        if (c.bool("twice") == true) session.loadServerPrefs("en")
        mapOf(
            "palette" to session.prefs.value.palette,
            "locale" to session.prefs.value.locale,
            "requests" to summarise(SessionSupport.requests()),
        )
    }

    @Test fun prefsSave() = Parity.check("maurice", "session-prefs-save") { c ->
        val store = MauriceFixture.store()
        val session = Session(store, "maurice")
        session.setPrefs(Prefs(palette = c.string("palette") ?: "auto", locale = c.string("locale")))
        val localeNow = session.prefs.value.locale
        // What the device keeps: read back as a fresh launch would.
        val again = Session(store, "maurice")
        mapOf(
            "localeBeforeRelaunch" to localeNow,
            "palette" to again.prefs.value.palette,
            "locale" to again.prefs.value.locale,
            "requests" to SessionSupport.requests(),
        )
    }
}
