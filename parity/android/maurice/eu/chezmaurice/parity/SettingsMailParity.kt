package eu.chezmaurice.parity

import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.settings.ContactAccount
import eu.chezmaurice.core.settings.MailAccount
import eu.chezmaurice.core.settings.MailScan
import eu.chezmaurice.core.settings.SettingsApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/** Settings › Mail and Address books (lot Settings). */
class SettingsMailParity {
    @Test fun settingsMail() = Parity.check("maurice", "settings-mail") { c ->
        when (c.string("kind")) {
            "mailProvider" -> {
                val p = provider("eu.chezmaurice.maurice.settings.MailProvider", c.string("address") ?: "") ?: return@check null
                val page = call(p, "getPage") as Pair<*, *>?
                mapOf(
                    "help" to call(p, "getHelp"),
                    "pageKey" to page?.first, "pageURL" to page?.second,
                    "possible" to call(p, "getPossible"),
                    "wantsAppPassword" to call(p, "getWantsAppPassword"),
                )
            }
            "contactProvider" -> {
                val p = provider("eu.chezmaurice.maurice.settings.ContactProvider", c.string("username") ?: "")!!
                val page = call(p, "getPage") as Pair<*, *>?
                mapOf(
                    "help" to call(p, "getHelp"),
                    "pageKey" to page?.first, "pageURL" to page?.second,
                    "possible" to call(p, "getPossible"),
                    "needsServer" to call(p, "getNeedsServer"),
                )
            }
            "decode" -> {
                val text = c.text("json")
                when (c.string("type")) {
                    // As the panes load them: SettingsApi's loaders, over the wire (null: unreadable).
                    "scan" -> served("/api/mail-accounts/scan", c.get("json")) { it.mailScan() }?.let(::scan) ?: "undecodable"
                    "mailAccounts" -> served("/api/mail-accounts", c.get("json")) { it.mailAccounts() }?.map(::mail) ?: "undecodable"
                    "contactAccounts" -> served("/api/contact-accounts", c.get("json")) { it.contactAccounts() }?.map(::contact) ?: "undecodable"
                    // One row, as an add, a check or a new password answers it.
                    "mailAccount" -> runCatching { MauriceJson.decodeFromString(MailAccount.serializer(), text) }.getOrNull()?.let(::mail) ?: "undecodable"
                    "contactAccount" -> runCatching { MauriceJson.decodeFromString(ContactAccount.serializer(), text) }.getOrNull()?.let(::contact) ?: "undecodable"
                    else -> "unknown type"
                }
            }
            else -> mapOf("unknown kind" to c.string("kind"))
        }
    }

    /** `MailProvider.of` / `ContactProvider.of`: file-private enums of MailPanes.kt. */
    private fun provider(className: String, arg: String): Any? {
        val cls = Class.forName(className)
        val companion = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        return companion.javaClass.getDeclaredMethod("of", String::class.java).apply { isAccessible = true }.invoke(companion, arg)
    }

    /** A property getter of the file-private enum. */
    private fun call(p: Any, getter: String): Any? =
        p.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(p)

    /** The household answers [json] on GET [path]; [load] reads it through SettingsApi. */
    private suspend fun <T> served(path: String, json: JsonElement?, load: suspend (SettingsApi) -> T): T? {
        Parity.reset(JsonArray(listOf(buildJsonObject {
            put("method", "GET"); put("path", path); put("json", json ?: JsonNull)
        })))
        return load(SettingsApi(MauriceFixture.session().api()!!))
    }

    private fun mail(a: MailAccount) = mapOf("id" to a.id, "address" to a.address, "provider" to a.provider, "host" to a.host, "state" to a.state, "lastError" to a.lastError)

    private fun contact(a: ContactAccount) = mapOf(
        "id" to a.id, "url" to a.url, "username" to a.username, "provider" to a.provider, "state" to a.state,
        "lastError" to a.lastError, "syncedAt" to a.syncedAt, "cards" to a.cards,
    )

    private fun scan(s: MailScan) = mapOf(
        "state" to s.state, "running" to s.running, "messages" to s.messages, "seen" to s.seen,
        "lastError" to s.lastError, "error" to s.error,
        "reading" to s.reading?.let { mapOf("state" to it.state, "decidedAt" to it.decidedAt, "years" to it.years) },
        "mailboxes" to s.mailboxes?.map { b ->
            mapOf(
                "address" to b.address, "messages" to b.messages, "untriaged" to b.untriaged,
                "reading" to mapOf("window" to b.reading.window, "toLight" to b.reading.toLight, "kept" to b.reading.kept,
                    "skipped" to b.reading.skipped, "toRead" to b.reading.toRead, "read" to b.reading.read),
                "estimate" to b.estimate?.let { mapOf("toSort" to it.toSort, "toRead" to it.toRead, "hours" to it.hours, "euros" to it.euros, "pending" to it.pending) },
                "senders" to b.topSenders?.map { mapOf("sender" to it.sender, "messages" to it.messages, "setAside" to it.setAside, "ruleDays" to it.ruleDays) },
                "approved" to b.approved,
            )
        },
        "documents" to s.documents?.let { mapOf("stage" to it.stage, "done" to it.done, "total" to it.total, "written" to it.written) },
    )

}
