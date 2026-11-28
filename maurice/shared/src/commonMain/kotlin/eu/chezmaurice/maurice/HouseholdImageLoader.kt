package eu.chezmaurice.maurice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult
import eu.chezmaurice.core.net.HouseholdImages
import eu.chezmaurice.core.session.Session

/** Adds the member's session to a picture of the household on screen (`HouseholdImages`). */
private object HouseholdImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val auth = HouseholdImages.authorization(url) ?: return chain.proceed()
        val headers = NetworkHeaders.Builder().set("Authorization", auth).build()
        return chain.withRequest(request.newBuilder().httpHeaders(headers).build()).proceed()
    }
}

/**
 * The app's image loader, with the household's pictures asked for with the
 * member's session (photos in conversations, 28 November 2026), and the
 * household on screen kept up to date for it. Placed once at the app's root,
 * in Maurice and in Carnet.
 */
@Composable
fun HouseholdImageLoader(session: Session) {
    val state by session.state.collectAsState()
    val current = state.current
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(HouseholdImageInterceptor) }.build()
    }
    // Again when the household, its address (a move) or its member changes.
    LaunchedEffect(current?.id, current?.serverUrl, current?.activeMemberId) {
        val api = session.api()
        val token = api?.token
        HouseholdImages.current = if (api != null && token != null) api.baseUrl to token else null
    }
}
