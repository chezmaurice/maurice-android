package eu.chezmaurice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the apps need beyond material-icons-core, drawn from the
 * Material icon paths (Apache 2.0) — cheaper than the whole extended set.
 */
object MauriceIcons {
    val ContentCopy by lazy {
        icon("ContentCopy", "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z")
    }
    val Mic by lazy {
        icon("Mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    }
    val Photo by lazy {
        icon("Photo", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }
    val OpenInNew by lazy {
        icon("OpenInNew", "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z")
    }

    val ChatBubble by lazy {
        icon("ChatBubble", "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z")
    }

    /** `doc.on.clipboard`: the + menu's Paste Image. */
    val ContentPaste by lazy {
        icon("ContentPaste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z")
    }

    /** `dollarsign.circle`: the coin under a reply, what it cost. */
    val Coin by lazy {
        icon("Coin", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13.41,18.09v1.91h-2.67v-1.93c-1.71,-0.36 -3.16,-1.46 -3.27,-3.4h1.96c0.1,1.05 0.82,1.87 2.65,1.87 1.96,0 2.4,-0.98 2.4,-1.59 0,-0.83 -0.44,-1.61 -2.67,-2.14 -2.48,-0.6 -4.18,-1.62 -4.18,-3.67 0,-1.72 1.39,-2.84 3.11,-3.21V4h2.67v1.95c1.86,0.45 2.79,1.86 2.85,3.39H14.3c-0.05,-1.11 -0.64,-1.87 -2.22,-1.87 -1.5,0 -2.4,0.68 -2.4,1.64 0,0.84 0.65,1.39 2.67,1.91s4.18,1.39 4.18,3.91c-0.01,1.83 -1.38,2.83 -3.12,3.16z")
    }

    /** `arrow.up.right`: a row that opens its entry elsewhere. */
    val NorthEast by lazy {
        icon("NorthEast", "M9,5v2h6.59L4,18.59 5.41,20 17,8.41V15h2V5z")
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
