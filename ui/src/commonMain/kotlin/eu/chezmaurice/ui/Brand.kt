package eu.chezmaurice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.chezmaurice.ui.generated.resources.Res
import eu.chezmaurice.ui.generated.resources.maurice_logomark
import org.jetbrains.compose.resources.painterResource

/** Maurice's hat, tinted — `BoaterHat.swift`, same footprint box. */
@Composable
fun BoaterHat(size: Dp = 18.dp, color: Color = LocalMauriceTheme.current.ink, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.maurice_logomark),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width = size * 1.15f, height = size * 0.85f),
    )
}

/** "#rrggbb" → Color; falls back to the neutral warm grey. */
fun hexColor(hex: String?, fallback: Color = Color(0xFF6B6460)): Color {
    val clean = hex?.removePrefix("#") ?: return fallback
    val value = clean.toLongOrNull(16) ?: return fallback
    return when (clean.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> fallback
    }
}

/**
 * A member's face: their picture when there is one, else their initial on
 * their colour. [imageUrl] is already absolute.
 */
@Composable
fun Avatar(name: String, color: String?, imageUrl: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(hexColor(color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (imageUrl != null) {
            AsyncImage(model = imageUrl, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}
