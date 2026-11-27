package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.settings.ThinkingPulsePreference
import eu.chezmaurice.core.settings.ThinkingPulseStyle
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The device's choice of pulse, provided by `MauriceApp`; none in previews. */
val LocalThinkingPulse = staticCompositionLocalOf<ThinkingPulsePreference?> { null }

/** The pulse chosen in Settings, the default where none is provided. */
@Composable
fun chosenThinkingPulse(): ThinkingPulseStyle {
    val pref = LocalThinkingPulse.current ?: return ThinkingPulseStyle.default
    val style by pref.style.collectAsState()
    return style
}

/**
 * The one progress mark of a turn — `ThinkingPulse.swift`: [style] drawn in a
 * box of 1.5 × [size] by [size], on the same cycles as on iOS.
 */
@Composable
fun ThinkingPulse(
    style: ThinkingPulseStyle = chosenThinkingPulse(),
    color: Color = LocalMauriceTheme.current.inkSoft,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val side = size // DrawScope has a `size` of its own
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t = (now - start) / 1e9f }
    }
    Box(modifier.size(width = size * 1.5f, height = size), contentAlignment = Alignment.Center) {
        when (style) {
            ThinkingPulseStyle.Hat -> {
                // A slow nod: a little tilt, a little bob, out of phase so it rolls.
                BoaterHat(
                    size = size * 0.95f, color = color,
                    modifier = Modifier.graphicsLayer {
                        val w = t * 2 * PI.toFloat() / 1.8f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        rotationZ = sin(w) * 9f
                        translationY = sin(w + 1.2f) * 1.2f * density
                    },
                )
            }
            else -> Canvas(Modifier.size(width = side * 1.5f, height = side)) {
                val s = side.toPx()
                val c = center
                when (style) {
                    ThinkingPulseStyle.Halo -> {
                        // A 1.6 s cycle: the ring leaves the dot at 0 and is gone by 1.
                        val phase = (t / 1.6f) % 1f
                        val breath = 0.85f + 0.15f * sin(t * 2 * PI.toFloat() / 1.6f)
                        val line = 1.2f * density
                        val ring = s * (0.35f + 0.65f * phase) / 2
                        drawCircle(color.copy(alpha = 0.7f * (1 - phase)), radius = max(0f, ring - line / 2), center = c, style = Stroke(line))
                        drawCircle(color, radius = s * 0.38f * breath / 2, center = c)
                    }
                    ThinkingPulseStyle.Orbit -> {
                        val r = s * 0.36f
                        val angle = t * 2 * PI.toFloat() / 1.4f
                        for (i in 0 until 3) {
                            val a = angle - i * 0.55f
                            val k = 1 - i * 0.3f
                            drawCircle(
                                color.copy(alpha = color.alpha * k),
                                radius = (s * 0.22f * k + density) / 2,
                                center = Offset(c.x + cos(a) * r, c.y + sin(a) * r),
                            )
                        }
                    }
                    ThinkingPulseStyle.Stroke -> {
                        // Draws left to right over the first half of a 1.4 s
                        // cycle, then the start catches up with the end.
                        val phase = (t / 1.4f) % 1f
                        val head = min(1f, phase * 2)
                        val tail = max(0f, phase * 2 - 1)
                        val width = s * 1.4f
                        val thick = s * 0.16f
                        val left = c.x - width / 2 + thick / 2
                        val run = width - thick
                        if (head > tail) drawLine(
                            color, Offset(left + run * tail, c.y), Offset(left + run * head, c.y),
                            strokeWidth = thick, cap = StrokeCap.Round,
                        )
                    }
                    ThinkingPulseStyle.Hat -> Unit
                }
            }
        }
    }
}
