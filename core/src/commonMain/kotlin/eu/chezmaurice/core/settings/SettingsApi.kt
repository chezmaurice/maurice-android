package eu.chezmaurice.core.settings

import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.toApiError
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A file the member picked, read as a stream: an export can weigh hundreds of megabytes. */
class Upload(val name: String, val size: Long?, val open: () -> ByteReadChannel)

// ── Members and invitations ──────────────────────────────

@Serializable data class OpenInvite(val code: String, @SerialName("expires_at") val expiresAt: String = "")
@Serializable private data class OpenInvites(val invites: List<OpenInvite> = emptyList())
@Serializable private data class CurrentInvite(val invite: OpenInvite? = null)
@Serializable private data class DeviceCodeState(val state: String)
@Serializable private data class HouseholdFace(val name: String)

/**
 * A line of the register of interventions (`GET /api/me/events?v=2`, server
 * `services/accountEvents.ts`): `device_by_code` (a device let in by a code
 * the admin made), `device_at_request` (one the member asked for),
 * `household_export`, `operator_logs`, `operator_data` — with the reason the
 * admin or the host gave, when there is one. A kind this build does not know
 * is kept, and told as a device's (as iOS).
 */
@Serializable data class AccountEvent(
    val id: Int,
    val kind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("seen_at") val seenAt: String? = null,
    val reason: String? = null,
)

/**
 * Why the admin makes a device code for someone else's account (`POST
 * /api/users/:id/invite`): the member reads it in the register.
 */
enum class DeviceCodePurpose(val wire: String) {
    /** They asked for it: a new phone, a forgotten PIN. */
    MemberRequest("member_request"),
    /** The admin goes into the account themselves, and says why. */
    AdminEntry("admin_entry"),
}
@Serializable private data class AccountEvents(val events: List<AccountEvent> = emptyList())

// ── Files ────────────────────────────────────────────────

@Serializable data class LibraryFolder(val id: String, @SerialName("parent_id") val parentId: String? = null, val name: String)
@Serializable data class LibraryFile(
    val id: String, @SerialName("folder_id") val folderId: String? = null, val name: String, val kind: String = "file",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)
@Serializable data class LibrarySummary(val count: Int = 0, @SerialName("size_bytes") val sizeBytes: Long = 0, @SerialName("quota_bytes") val quotaBytes: Long = 0)
@Serializable data class Library(val folders: List<LibraryFolder> = emptyList(), val files: List<LibraryFile> = emptyList(), val summary: LibrarySummary = LibrarySummary())

// ── Mail ─────────────────────────────────────────────────

@Serializable data class MailAccount(
    val id: String, val address: String, val provider: String? = null, val host: String? = null,
    /** unchecked | ok | error. */
    val state: String = "unchecked", @SerialName("last_error") val lastError: String? = null,
)
@Serializable private data class MailAccounts(val accounts: List<MailAccount> = emptyList())

@Serializable data class MailScan(
    val state: String = "idle",
    val running: Boolean = false,
    val messages: Int = 0,
    val seen: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    val error: String? = null,
    val reading: MailReading? = null,
    val mailboxes: List<Mailbox>? = null,
    val documents: MailDocuments? = null,
)
@Serializable data class MailDocuments(val stage: String = "", val done: Int = 0, val total: Int = 0, val written: Int = 0)
@Serializable data class Mailbox(
    val address: String, val messages: Int = 0, val untriaged: Int = 0, val reading: BoxReading = BoxReading(),
    val estimate: BoxEstimate? = null, @SerialName("top_senders") val topSenders: List<Sender>? = null, val approved: Boolean? = null,
)
@Serializable data class Sender(val sender: String, val messages: Int = 0, @SerialName("set_aside") val setAside: Int = 0, @SerialName("rule_days") val ruleDays: Int? = null)
@Serializable data class BoxReading(
    val window: Int = 0, @SerialName("to_light") val toLight: Int = 0, val kept: Int = 0, val skipped: Int = 0,
    @SerialName("to_read") val toRead: Int = 0, val read: Int = 0,
)
/** What is left to read in a mailbox; [euros] come for the operator only, null for a member. */
@Serializable data class BoxEstimate(
    @SerialName("to_sort") val toSort: Int = 0, @SerialName("to_read") val toRead: Int = 0, val hours: Double = 0.0,
    val euros: Double? = null, val pending: Boolean = false,
)
@Serializable data class MailReading(val state: String = "", @SerialName("decided_at") val decidedAt: String? = null, val years: Int? = null)
@Serializable data class ForgetResult(val gone: Int = 0, val changed: Int = 0, val removed: Int = 0, val history: String = "")

// ── Address books ────────────────────────────────────────

@Serializable data class ContactAccount(
    val id: String, val url: String? = null, val username: String, val provider: String? = null,
    val state: String = "unchecked", @SerialName("last_error") val lastError: String? = null,
    @SerialName("synced_at") val syncedAt: String? = null, val cards: Int = 0,
)
@Serializable private data class ContactAccounts(val accounts: List<ContactAccount> = emptyList())

// ── History import ───────────────────────────────────────

@Serializable data class ImportRun(
    val id: String, val provider: String = "", val conversations: Int = 0, val messages: Int = 0,
    @SerialName("ran_at") val ranAt: String = "", val status: String = "",
)
@Serializable data class ImportHistory(val provider: String = "", val watermark: String? = null, val history: List<ImportRun> = emptyList())
@Serializable private data class ImportJob(@SerialName("job_id") val jobId: String)
@Serializable data class ImportStatus(
    val phase: String? = null, val done: Int? = null, val total: Int? = null, val status: String? = null,
    val error: String? = null, val result: Result? = null,
) {
    @Serializable data class Result(val conversations: Int = 0, val messages: Int = 0)
}

// ── My data ──────────────────────────────────────────────

@Serializable data class GitAccess(val url: String, val username: String, val token: String, @SerialName("export_conversations") val exportConversations: Boolean = false)
@Serializable private data class GitExport(@SerialName("export_conversations") val exportConversations: Boolean? = null)
@Serializable data class MemberImport(val report: Report) {
    @Serializable data class Report(val conversations: Int = 0, val messages: Int = 0, val files: Int = 0, val notes: Int = 0)
}
@Serializable data class EraseAnswer(val scope: String = "", val corpus: Boolean = true, val residual: Residual = Residual()) {
    @Serializable data class Residual(
        @SerialName("garden_remotes") val gardenRemotes: List<String> = emptyList(),
        @SerialName("published_site") val publishedSite: Boolean = false,
        @SerialName("household_archives") val householdArchives: Int = 0,
    )
}

/**
 * The settings' routes (`SettingsView.swift`, `MyDataView.swift`,
 * `ImportConversationsView.swift`): members and invitations, the file
 * library, mailboxes and address books, the history import, the member's
 * own data. Loaders answer null when the server could not be read.
 */
/**
 * One sign-in of the member's (`GET /api/me/sessions`, 26 November 2026):
 * `id` names the session to close it and opens nothing; [current] is the one
 * asking; [device] what the device last said of itself, null when it never did.
 */
@Serializable data class OwnSession(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    val current: Boolean = false,
    val device: Device? = null,
) {
    @Serializable data class Device(
        val platform: String? = null,
        val model: String? = null,
        val app: String? = null,
        @SerialName("app_version") val appVersion: String? = null,
    )

    /** "iPhone", "iPad", "Mac", "Android", "Windows", "Linux"… from the platform reported; null when none (`DevicesView`). */
    val family: String? get() {
        val platform = device?.platform?.takeIf { it.isNotEmpty() } ?: return null
        val p = platform.lowercase()
        return when {
            "ipad" in p -> "iPad"
            "ios" in p || "iphone" in p -> "iPhone"
            "mac" in p -> "Mac"
            "android" in p -> "Android"
            "windows" in p -> "Windows"
            "linux" in p -> "Linux"
            else -> platform
        }
    }

    /** The SF Symbol name of its glyph, as on iOS. */
    val icon: String get() = when (family) {
        "iPhone", "Android" -> "iphone"
        "iPad" -> "ipad"
        "Mac" -> "laptopcomputer"
        null -> "questionmark.circle"
        else -> "desktopcomputer"
    }

    /** "Maurice 0.5.20 · Pixel 8": the app and its version, then the maker's model. */
    val detail: String? get() {
        val parts = mutableListOf<String>()
        device?.app?.takeIf { it.isNotEmpty() }?.let { app ->
            parts += listOfNotNull(app.replaceFirstChar { it.uppercase() }, device.appVersion).joinToString(" ")
        }
        device?.model?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

@Serializable private data class OwnSessions(val sessions: List<OwnSession> = emptyList())
@Serializable private data class ClosedOthers(val closed: Int = 0)

class SettingsApi(private val api: ApiClient) {

    // ── Members ────────────────────────────────────────────

    suspend fun members(): List<Member> = api.get("/api/users")
    suspend fun invites(): List<OpenInvite> = api.get<OpenInvites>("/api/users/invites").invites
    /** An open invitation: someone new, single use, seven days. */
    suspend fun invite(): OpenInvite = api.post("/api/users/invites")
    suspend fun revokeInvite(code: String) { api.send(HttpMethod.Delete, "/api/users/invites/$code") }
    /** A member's current code for a new device, if one is open. */
    suspend fun currentDeviceCode(memberId: String): OpenInvite? = api.get<CurrentInvite>("/api/users/$memberId/invite").invite
    /**
     * A fresh code for a new device of the member's, replacing the old one.
     * For someone else's account the admin says why ([purpose]; a [reason] is
     * required with [DeviceCodePurpose.AdminEntry], else `400 reason_required`);
     * for the admin's own account, neither is sent.
     */
    suspend fun newDeviceCode(memberId: String, purpose: DeviceCodePurpose? = null, reason: String? = null): OpenInvite =
        api.post("/api/users/$memberId/invite", buildJsonObject {
            purpose?.let { put("purpose", it.wire) }
            reason?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reason", it) }
        })
    suspend fun revokeDeviceCode(memberId: String) { api.send(HttpMethod.Delete, "/api/users/$memberId/invite") }
    /**
     * The admin changes the name and colour everyone sees of a member
     * (`PATCH /api/users/:id`); answers the member as the server kept them.
     * Never the PIN: a forgotten one goes through a new device's code.
     */
    suspend fun updateMember(id: String, displayName: String, avatarColor: String): Member =
        api.decode(api.send(HttpMethod.Patch, "/api/users/$id", buildJsonObject {
            put("display_name", displayName); put("avatar_color", avatarColor)
        }))
    /** The member's account and everything that was theirs (`DELETE /api/users/:id`). */
    suspend fun deleteMember(id: String) { api.send(HttpMethod.Delete, "/api/users/$id") }
    /** The name of this Maurice, as everyone's list shows it (`PATCH /api/admin/settings`); answers the name kept. */
    suspend fun renameHousehold(name: String): String =
        api.decode<HouseholdFace>(api.send(HttpMethod.Patch, "/api/admin/settings", buildJsonObject { put("name", name) })).name

    /** A code for another device of the signed-in member's own (any member but a guest): single use, ten minutes. */
    suspend fun ownDeviceCode(): OpenInvite = api.post("/api/me/device-code")

    /** The devices signed in as this member, the most recently used first (`GET /api/me/sessions`). */
    suspend fun ownSessions(): List<OwnSession> = api.get<OwnSessions>("/api/me/sessions").sessions

    /** Signs one device out (`DELETE /api/me/sessions/:id`); one already closed from elsewhere (404) is not an error. */
    suspend fun closeSession(id: String) {
        try { api.send(HttpMethod.Delete, "/api/me/sessions/$id") } catch (e: ApiError) { if (e.status != 404) throw e }
    }

    /** Signs every other device out (`POST /api/me/sessions/close-others`): how many were. */
    suspend fun closeOtherSessions(): Int = api.post<ClosedOthers>("/api/me/sessions/close-others").closed
    /** "pending", "used" or "expired"; null when the server cannot be reached. */
    suspend fun ownDeviceCodeState(code: String): String? = quietly { api.get<DeviceCodeState>("/api/me/device-code/$code").state }

    // ── Files ──────────────────────────────────────────────

    suspend fun library(): Library? = quietly { api.get("/api/files") }
    /** Into the library ([folderId] null: its root); answers the new file. */
    suspend fun upload(file: Upload, folderId: String? = null): LibraryFile =
        eu.chezmaurice.core.net.MauriceJson.decodeFromString(LibraryFile.serializer(), multipart("/api/files/upload", file, folderId?.let { mapOf("folder_id" to it) }.orEmpty()))
    /**
     * The register of interventions on this account (`GET /api/me/events?v=2`;
     * without `v=2` the server keeps to the two kinds older apps know). Null
     * when the server cannot say (too old, unreachable).
     */
    suspend fun accountEvents(): List<AccountEvent>? = quietly { api.get<AccountEvents>("/api/me/events?v=2").events }
    /** The member has read the register: the orange dot goes out. */
    suspend fun accountEventsSeen() { quietly { api.post<JsonObject>("/api/me/events/seen?v=2", buildJsonObject {}) } }

    /**
     * The administrator's own password (`POST /api/auth/password`): 401 the
     * current one is wrong, 400 the new one is refused, 429 too many tries.
     * The admin's other devices are signed out.
     */
    suspend fun changePassword(current: String, new: String) {
        api.post<JsonObject>("/api/auth/password", buildJsonObject { put("current_password", current); put("new_password", new) })
    }

    suspend fun newFolder(name: String) { api.post<JsonObject>("/api/files/folder", buildJsonObject { put("parent_id", JsonNull); put("name", name) }) }
    suspend fun renameFolder(id: String, name: String) { api.send(HttpMethod.Patch, "/api/files/folder/$id", buildJsonObject { put("name", name) }) }
    suspend fun deleteFolder(id: String) { api.send(HttpMethod.Delete, "/api/files/folder/$id") }
    suspend fun renameFile(id: String, name: String) { api.send(HttpMethod.Patch, "/api/files/$id", buildJsonObject { put("name", name) }) }
    suspend fun deleteFile(id: String) { api.send(HttpMethod.Delete, "/api/files/$id") }

    // ── Mail ───────────────────────────────────────────────

    suspend fun mailAccounts(): List<MailAccount>? = quietly { api.get<MailAccounts>("/api/mail-accounts").accounts }
    suspend fun mailScan(): MailScan? = quietly { api.get("/api/mail-accounts/scan") }

    /** Checks the login before keeping it: 422 when the server refused or could not be reached. */
    suspend fun addMail(address: String, password: String, provider: String?, host: String?, port: Int?, username: String?): MailAccount =
        api.post("/api/mail-accounts", buildJsonObject {
            put("address", address); put("password", password)
            provider?.let { put("provider", it) }; host?.let { put("host", it) }
            port?.let { put("port", it) }; username?.let { put("username", it) }
        }, timeoutMillis = 90_000)

    suspend fun checkMail(id: String): MailAccount = api.post("/api/mail-accounts/$id/check", timeoutMillis = 90_000)
    suspend fun mailPassword(id: String, password: String): MailAccount =
        api.decode(api.send(HttpMethod.Put, "/api/mail-accounts/$id/password", buildJsonObject { put("password", password) }, timeoutMillis = 90_000))
    suspend fun removeMail(id: String) { api.send(HttpMethod.Delete, "/api/mail-accounts/$id") }
    /** Forget a mailbox and what came of it; [history] rewrites the garden's history too. */
    suspend fun forgetMail(id: String, history: Boolean): ForgetResult =
        api.post("/api/mail-accounts/$id/forget", buildJsonObject { put("history", history) }, timeoutMillis = 300_000)

    suspend fun startScan(): MailScan = api.post("/api/mail-accounts/scan")
    /** approve | decline: the member's word on Maurice reading their mail. */
    suspend fun decideReading(action: String): MailScan = api.post("/api/mail-accounts/reading", buildJsonObject { put("action", action) })
    suspend fun readNow(): MailScan = api.post("/api/mail-accounts/reading/run")
    suspend fun readThisBox(id: String): MailScan = api.post("/api/mail-accounts/$id/reading")
    suspend fun widenWindow(years: Int): MailScan = api.post("/api/mail-accounts/reading/window", buildJsonObject { put("years", years) })
    /** null: everything from that sender; 0: nothing; n: the last n days. */
    suspend fun senderRule(address: String, days: Int?): MailScan = api.decode(api.send(HttpMethod.Put, "/api/mail-accounts/senders", JsonObject(mapOf(
        "address" to JsonPrimitive(address), "days" to (days?.let(::JsonPrimitive) ?: JsonNull),
    ))))

    // ── Address books ──────────────────────────────────────

    suspend fun contactAccounts(): List<ContactAccount>? = quietly { api.get<ContactAccounts>("/api/contact-accounts").accounts }
    suspend fun addContacts(username: String, password: String, url: String?): ContactAccount =
        api.post("/api/contact-accounts", buildJsonObject { put("username", username); put("password", password); url?.let { put("url", it) } }, timeoutMillis = 120_000)
    suspend fun syncContacts(id: String): ContactAccount = api.post("/api/contact-accounts/$id/sync", timeoutMillis = 120_000)
    suspend fun contactsPassword(id: String, password: String): ContactAccount =
        api.decode(api.send(HttpMethod.Put, "/api/contact-accounts/$id/password", buildJsonObject { put("password", password) }, timeoutMillis = 120_000))
    suspend fun removeContacts(id: String) { api.send(HttpMethod.Delete, "/api/contact-accounts/$id") }

    // ── History import ─────────────────────────────────────

    suspend fun importHistory(provider: String): ImportHistory? = quietly { api.get("/api/import/history?provider=$provider") }
    /** Both providers' histories; synced once either has a watermark. */
    suspend fun imports(): List<ImportHistory>? = listOf("anthropic", "chatgpt").mapNotNull { importHistory(it) }.takeIf { it.isNotEmpty() }
    /** Starts the job; answers its id, to follow with [importStatus]. */
    suspend fun startImport(provider: String, file: Upload): String = MauriceJsonDecode.job(multipart("/api/import?provider=$provider", file))
    suspend fun importStatus(job: String): ImportStatus? = quietly { api.get("/api/import/status?job=$job") }

    // ── My data ────────────────────────────────────────────

    /** Everything the server holds of the member, as a tar.gz, streamed into [sink]. */
    suspend fun export(sink: suspend (ByteReadChannel) -> Unit) {
        api.http.prepareRequest(api.url("/api/me/export")) {
            method = HttpMethod.Get
            api.token?.let { bearerAuth(it) }
            timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 120_000 }
        }.execute { response ->
            if (!response.status.isSuccess()) throw response.toApiError()
            sink(response.bodyAsChannel())
        }
    }

    /** Null: the member has no garden to clone (404). */
    suspend fun git(rotate: Boolean): GitAccess? = try {
        if (rotate) api.post("/api/me/git/rotate") else api.get("/api/me/git")
    } catch (e: ApiError) {
        if (e.status == 404) null else throw e
    }

    suspend fun setGitExport(conversations: Boolean): Boolean =
        api.decode<GitExport>(api.send(HttpMethod.Put, "/api/me/git/export", buildJsonObject { put("conversations", conversations) })).exportConversations ?: conversations

    suspend fun importMine(file: Upload): MemberImport.Report = MauriceJsonDecode.memberImport(multipart("/api/me/import", file))

    /** data: everything but the account; account: the account too. Typed username, and the password or PIN. */
    suspend fun erase(scope: String, confirm: String, secret: String): EraseAnswer = api.post("/api/me/erase", buildJsonObject {
        put("scope", scope); put("confirm", confirm)
        if (secret.isNotEmpty()) { put("password", secret); put("pin", secret) }
    }, timeoutMillis = 600_000)

    // ── Plumbing ───────────────────────────────────────────

    /** `multipart/form-data` with the file as `file`, streamed. Answers the response text. */
    private suspend fun multipart(path: String, file: Upload, fields: Map<String, String> = emptyMap()): String =
        api.http.prepareRequest(api.url(path)) {
            method = HttpMethod.Post
            api.token?.let { bearerAuth(it) }
            timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 300_000 }
            setBody(MultiPartFormDataContent(formData {
                for ((k, v) in fields) append(k, v)
                append("file", ChannelProvider(file.size) { file.open() }, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "")}\"")
                })
            }))
        }.execute { response ->
            if (!response.status.isSuccess()) throw response.toApiError()
            response.bodyAsText()
        }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

private object MauriceJsonDecode {
    fun job(text: String) = eu.chezmaurice.core.net.MauriceJson.decodeFromString(ImportJob.serializer(), text).jobId
    fun memberImport(text: String) = eu.chezmaurice.core.net.MauriceJson.decodeFromString(MemberImport.serializer(), text).report
}
