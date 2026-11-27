package eu.chezmaurice.carnet.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.gardenImageUrl
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.ui.LocalMauriceTheme

/** The signed-in member's client: the base address and the token covers need. */
val LocalApi = staticCompositionLocalOf<ApiClient?> { null }

/**
 * An entry's cover (`CoverImage`): its garden image (served open), else its
 * book's cover from the library (which wants the token), else its letter.
 */
@Composable
fun CoverImage(entry: ShelfEntry, width: Dp = 46.dp, height: Dp = 64.dp) {
    val api = LocalApi.current
    val garden = api?.let { gardenImageUrl(it.baseUrl, entry.image) }
    val book = entry.source?.bookId
    when {
        garden != null -> Cover(garden, authed = false, entry.coverLetter, width, height)
        book != null -> BookCover(book, entry.coverLetter, width, height)
        else -> GridCover(entry.coverLetter, width, height)
    }
}

@Composable
fun BookCover(bookId: Int, letter: String, width: Dp = 46.dp, height: Dp = 64.dp) {
    val api = LocalApi.current ?: return GridCover(letter, width, height)
    Cover(api.url("/api/v1/calibre/books/$bookId/cover"), authed = true, letter, width, height)
}

@Composable
private fun Cover(url: String, authed: Boolean, letter: String, width: Dp, height: Dp) {
    val api = LocalApi.current
    val context = LocalPlatformContext.current
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return GridCover(letter, width, height)
    val request = remember(url, api?.token) {
        ImageRequest.Builder(context).data(url).apply {
            if (authed) api?.token?.let { httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $it").build()) }
        }.build()
    }
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.size(width, height)) {
        GridCover(letter, width, height)
        AsyncImage(
            request, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true },
            modifier = Modifier.size(width, height).clip(shape).border(0.5.dp, LocalMauriceTheme.current.rule, shape),
        )
    }
}
