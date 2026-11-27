package eu.chezmaurice.carnet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.MauriceTheme

// The two faces of an entry are two papers (`Paper.swift`): mine is dotted
// on the page's colour, the shared one plain on the raised surface. The sheet
// has its bottom-right corner folded, showing the back.

enum class Face { Mine, Shared }
enum class Back { Mine, Shared, Blank }

/** A rounded rectangle with its bottom-right corner cut for the fold. */
class SheetShape(private val radius: Dp = 14.dp, private val fold: Dp = 46.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val f = with(density) { fold.toPx() }
        val p = Path().apply {
            moveTo(r, 0f)
            lineTo(size.width - r, 0f)
            quadraticTo(size.width, 0f, size.width, r)
            lineTo(size.width, size.height - f)
            lineTo(size.width - f, size.height)
            lineTo(r, size.height)
            quadraticTo(0f, size.height, 0f, size.height - r)
            lineTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            close()
        }
        return Outline.Generic(p)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dots(color: Color) {
    val step = 7.dp.toPx()
    val radius = 0.55.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(color, radius, Offset(x, y)); x += step }
        y += step
    }
}

/** The paper of a face behind a shape: dotted for mine, plain for the shared one. */
fun Modifier.paper(face: Face, shape: Shape, theme: MauriceTheme): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }
    drawPath(path, if (face == Face.Mine) theme.bg else theme.surface)
    if (face == Face.Mine) clipPath(path) { dots(theme.ruleHard) }
    drawPath(path, theme.ruleHard, style = Stroke(0.5.dp.toPx()))
}

/** A capsule of mine-paper, for the note field and the fiche button. */
fun Modifier.minePaper(theme: MauriceTheme, radius: Dp = 22.dp): Modifier = drawBehind {
    val rr = RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius.toPx()))
    val path = Path().apply { addRoundRect(rr) }
    drawPath(path, theme.bg)
    clipPath(path) { dots(theme.ruleHard) }
    drawPath(path, theme.ruleHard, style = Stroke(0.5.dp.toPx()))
}

/** The folded corner: a triangle showing the back's paper. */
@Composable
fun FoldedCorner(back: Back, size: Dp = 46.dp) {
    val theme = LocalMauriceTheme.current
    Canvas(Modifier.size(size)) {
        val p = Path().apply { moveTo(0f, 0f); lineTo(this@Canvas.size.width, 0f); lineTo(0f, this@Canvas.size.height); close() }
        drawPath(p, if (back == Back.Shared) theme.surface else theme.bg)
        if (back == Back.Mine) clipPath(p) { dots(theme.ruleHard) }
        drawPath(p, theme.ruleHard, style = Stroke(1.dp.toPx(), pathEffect = if (back == Back.Blank) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null))
    }
}
