package eu.chezmaurice.core.session

import eu.chezmaurice.core.model.Health
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64

/**
 * Following a household that moved to another address (`HouseholdMove.swift`).
 *
 * A household that changes its name leaves a TXT record under the old one:
 * `_maurice.<old host>  TXT  "v=maurice1; moved=https://<new host>"`. When the
 * old address stops answering, the device reads that record, asks the new
 * address to sign a nonce of its own (`/api/health?proof=…`), and follows only
 * if the signature checks against the identity key it pinned at pairing.
 * What the household signs names its own address beside the nonce, and the
 * device follows only to the address that was signed: a server that merely
 * passes the question on to the household gets a proof for the household's
 * address, not for its own. A forged record can point anywhere; it cannot make
 * the device move.
 * The server's half is `services/householdKey.ts`; the record's spelling is in
 * `docs/moving-a-household.md`.
 */
object HouseholdMove {
    private const val PROOF_CONTEXT = "maurice-household-proof:v2\n"

    /**
     * The new address of the household at [serverUrl], if its old name says it
     * moved and the new address proves it holds [key]. Null otherwise.
     */
    suspend fun follow(serverUrl: String, key: String, http: HttpClient, txt: suspend (String) -> List<String> = ::txtRecords): String? {
        val host = runCatching { Url(serverUrl).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val target = runCatching { txt("_maurice.$host") }.getOrDefault(emptyList()).firstNotNullOfOrNull(::parse) ?: return null
        if (target == serverUrl.trimEnd('/')) return null
        return target.takeIf { proves(it, key, http) }
    }

    /** `v=maurice1; moved=https://…` → the address, without a trailing slash; null for anything else. */
    fun parse(record: String): String? {
        val fields = record.split(';').mapNotNull { part ->
            val kv = part.split('=', limit = 2).map { it.trim() }
            if (kv.size == 2) kv[0].lowercase() to kv[1] else null
        }.toMap()
        if (fields["v"] != "maurice1") return null
        val raw = fields["moved"] ?: return null
        // A host of its own: Url() would make up "localhost" for `https://`.
        if (!raw.startsWith("https://") || MauriceInvitation.hostOf(raw).isNullOrEmpty()) return null
        return raw.removeSuffix("/")
    }

    /**
     * `https://host[:port]` of an address, lower case: what a household signs as
     * its own. Null for anything that is not a plain http(s) address.
     */
    fun origin(serverUrl: String): String? {
        val url = runCatching { Url(serverUrl) }.getOrNull() ?: return null
        val scheme = url.protocol.name.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (!serverUrl.trim().lowercase().startsWith("$scheme://")) return null
        val host = url.host.lowercase()
        if (host.isEmpty() || url.user != null) return null
        val port = url.specifiedPort.takeIf { it != 0 && it != url.protocol.defaultPort }?.let { ":$it" } ?: ""
        return "$scheme://$host$port"
    }

    /** The bytes a household signs: context, the device's nonce, the address. */
    fun proofMessage(nonce: String, origin: String): ByteArray = "$PROOF_CONTEXT$nonce\n$origin".encodeToByteArray()

    /**
     * True when [serverUrl] signs a fresh nonce with the private half of [key],
     * for [serverUrl] itself: the address signed must be the one asked.
     */
    suspend fun proves(serverUrl: String, key: String, http: HttpClient): Boolean {
        val asked = origin(serverUrl) ?: return false
        val publicKey = base64Url(key)?.takeIf { it.size == 32 } ?: return false
        val nonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(24))
        val health = withTimeoutOrNull(8_000) {
            runCatching {
                val response = http.get("${serverUrl.trimEnd('/')}/api/health?proof=$nonce")
                if (response.status.value != 200) null
                else MauriceJson.decodeFromString(Health.serializer(), response.bodyAsText())
            }.getOrNull()
        } ?: return false
        if (health.identityKey != key || health.proofOrigin != asked) return false
        val signature = health.proof?.let(::base64Url)?.takeIf { it.size == 64 } ?: return false
        return ed25519Verify(publicKey, proofMessage(nonce, asked), signature)
    }

    fun base64Url(text: String): ByteArray? {
        var b = text.replace('-', '+').replace('_', '/')
        while (b.length % 4 != 0) b += "="
        val pad = b.length - b.trimEnd('=').length
        if (pad > 2) return null
        val out = ArrayList<Byte>(b.length / 4 * 3)
        var acc = 0
        var bits = 0
        for ((i, ch) in b.withIndex()) {
            if (ch == '=') { if (i < b.length - pad) return null; continue }
            val v = B64.indexOf(ch).takeIf { it >= 0 } ?: return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out += (acc shr bits and 0xff).toByte() }
        }
        return out.toByteArray()
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** A TXT record's rdata: length-prefixed strings, joined. */
    fun decodeTxt(rdata: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < rdata.size) {
            val n = rdata[i].toInt() and 0xff
            i++
            val end = minOf(i + n, rdata.size)
            out.append(rdata.decodeToString(i, end))
            i = end
        }
        return out.toString()
    }

    /**
     * The TXT records of a DNS response message (RFC 1035), each one's strings
     * joined: what Android's resolver hands back raw. Empty on a malformed or
     * failed answer.
     */
    fun txtOfResponse(message: ByteArray): List<String> = runCatching {
        fun u16(at: Int) = ((message[at].toInt() and 0xff) shl 8) or (message[at + 1].toInt() and 0xff)
        fun skipName(start: Int): Int {
            var i = start
            while (true) {
                val len = message[i].toInt() and 0xff
                when {
                    len == 0 -> return i + 1
                    len and 0xc0 == 0xc0 -> return i + 2 // a pointer ends the name
                    else -> i += 1 + len
                }
            }
        }
        if (u16(2) and 0x000f != 0) return emptyList() // RCODE: NXDOMAIN and the like
        val questions = u16(4)
        val answers = u16(6)
        var i = 12
        repeat(questions) { i = skipName(i) + 4 }
        val out = mutableListOf<String>()
        repeat(answers) {
            i = skipName(i)
            val type = u16(i)
            val length = u16(i + 8)
            val start = i + 10
            if (type == 16) out += decodeTxt(message.copyOfRange(start, start + length))
            i = start + length
        }
        out
    }.getOrDefault(emptyList())
}

/** The TXT records of [name] through the system's resolver, each one's strings joined. Empty on any failure or after 5 s. */
expect suspend fun txtRecords(name: String): List<String>

/** RFC 8032 Ed25519: does [signature] (64 bytes) sign [message] under [publicKey] (32 bytes)? */
expect fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

expect fun secureRandomBytes(count: Int): ByteArray
