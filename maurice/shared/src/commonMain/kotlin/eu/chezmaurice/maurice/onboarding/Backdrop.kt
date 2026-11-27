package eu.chezmaurice.maurice.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.chezmaurice.maurice.resources.Res
import eu.chezmaurice.ui.LocalMauriceTheme
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The ways in (welcome, "Who's here?") on the app icon's own ground, as on
 * iOS and the /join page (agreed 2 November 2026): the tile repeated, never
 * stretched, from the top left; every word on one card above it.
 */
private object Ground {
    /** One tile: eight blocks of 40 dp. */
    val TILE = 320.dp
    /** Maurice's light tile under the dark theme: the theme's background over it. */
    const val DARK_VEIL = 0.55f
    val CARD_MAX = 420.dp
    val CARD_RADIUS = 28.dp
    val CARD_PADDING = 24.dp
    /** Between the card and the screen's edge. */
    val MARGIN = 20.dp
    const val CARD_OPACITY = 0.92f
}

/** Room around the card. */
val EntryMargin = Ground.MARGIN

/**
 * The app's tile behind [content], in both themes: Maurice's light one,
 * veiled in the dark theme; Carnet's dark one, never veiled. The words on
 * the card follow the device's theme.
 */
@Composable
fun EntryGround(content: @Composable BoxScope.() -> Unit) {
    val brand = LocalAppBrand.current
    val theme = LocalMauriceTheme.current
    // The status bar follows the tile: light icons on Carnet's dark one, the theme's on Maurice's.
    if (brand.darkTile) eu.chezmaurice.maurice.platform.LightStatusBarIcons()
    val tile by produceState<ImageBitmap?>(null, brand.tile) {
        value = runCatching { Res.readBytes(brand.tile).decodeToImageBitmap() }.getOrNull()
    }
    Box(
        Modifier.fillMaxSize().background(theme.bg).drawBehind {
            val image = tile ?: return@drawBehind
            val side = Ground.TILE.toPx().toInt().coerceAtLeast(1)
            var y = 0
            while (y < size.height) {
                var x = 0
                while (x < size.width) {
                    drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset(x, y), IntSize(side, side))
                    x += side
                }
                y += side
            }
            if (!brand.darkTile && theme.isDark) drawRect(theme.bg.copy(alpha = Ground.DARK_VEIL))
        },
        content = content,
    )
}

/** The one card every word of a way in sits on. */
@Composable
fun EntryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(
        modifier.widthIn(max = Ground.CARD_MAX).fillMaxWidth()
            .clip(RoundedCornerShape(Ground.CARD_RADIUS))
            .background(theme.surface.copy(alpha = Ground.CARD_OPACITY))
            .padding(Ground.CARD_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
