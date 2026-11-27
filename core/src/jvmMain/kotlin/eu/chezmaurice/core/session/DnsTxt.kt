package eu.chezmaurice.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Hashtable
import javax.naming.directory.InitialDirContext

/**
 * Through JNDI's DNS provider, which asks the servers the system is set to
 * (resolv.conf on Linux, the adapters' on Windows): no third party is told
 * the name. JNDI hands each TXT record's strings already joined, unless they
 * come quoted one by one — then the quotes and spaces between go.
 */
actual suspend fun txtRecords(name: String): List<String> = withContext(Dispatchers.IO) {
    runCatching {
        val env = Hashtable<String, String>().apply {
            put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
            put("com.sun.jndi.dns.timeout.initial", "2000")
            put("com.sun.jndi.dns.timeout.retries", "2")
        }
        val context = InitialDirContext(env)
        try {
            val values = context.getAttributes("dns:/$name", arrayOf("TXT")).get("TXT") ?: return@runCatching emptyList()
            (0 until values.size()).map { joinQuoted(values.get(it).toString()) }
        } finally {
            context.close()
        }
    }.getOrDefault(emptyList())
}

/** `"v=maurice1; " "moved=https://x"` → `v=maurice1; moved=https://x`; an unquoted value as is. */
internal fun joinQuoted(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.startsWith('"')) return trimmed
    return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(trimmed).joinToString("") { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
}
