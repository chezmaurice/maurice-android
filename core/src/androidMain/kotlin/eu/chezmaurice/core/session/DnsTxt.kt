package eu.chezmaurice.core.session

import android.net.DnsResolver
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private val dnsExecutor = Executors.newSingleThreadExecutor()

/**
 * Through the system's resolver (`DnsResolver`, Android 10+), which honours
 * Private DNS: the name asked never goes to anyone the phone does not
 * already ask. It answers the raw message; HouseholdMove reads the TXT out.
 */
actual suspend fun txtRecords(name: String): List<String> = withTimeoutOrNull(5_000) {
    suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        DnsResolver.getInstance().rawQuery(
            null, name, DnsResolver.CLASS_IN, TYPE_TXT, DnsResolver.FLAG_EMPTY, dnsExecutor, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (cont.isActive) cont.resume(if (rcode == 0) HouseholdMove.txtOfResponse(answer) else emptyList())
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            },
        )
    }
} ?: emptyList()

private const val TYPE_TXT = 16
