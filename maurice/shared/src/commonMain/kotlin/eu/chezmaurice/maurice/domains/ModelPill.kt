package eu.chezmaurice.maurice.domains

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme

/** A provider's mark and name, as the household's palette has them (`ProviderStyle`). */
object ProviderStyle {
    fun fill(p: String): Brush = when (p) {
        "anthropic" -> Brush.linearGradient(listOf(Color(0xFFDA7758), Color(0xFFDA7758)))
        "openai" -> solid(0xFF202123)
        "mistral" -> Brush.linearGradient(listOf(Color(0xFFFCC73C), Color(0xFFFC6817)))
        "zai" -> solid(0xFF2D2D2D)
        "scaleway" -> solid(0xFF4F0599)
        "gemini", "google" -> Brush.linearGradient(listOf(Color(0xFF4D83EF), Color(0xFFCD6983)))
        "ollama" -> solid(0xFF00FF00)
        else -> solid(0xFF6B6460)
    }

    @Composable
    fun name(p: String): String = when (p) {
        "zai" -> "Z.AI"
        "gemini", "google" -> "GEMINI"
        "ollama" -> L("persona.provider.ollama")
        else -> p.uppercase()
    }

    private fun solid(argb: Long) = Brush.linearGradient(listOf(Color(argb), Color(argb)))
}

/**
 * The model the conversation's Maurice runs, switchable: the everyday one
 * keeps the choice per member, a domain on itself; it applies to this turn
 * and the next (`modelPill`).
 */
@Composable
fun ModelPill(mauriceId: String?, enabled: Boolean) {
    val store = LocalDomains.current ?: return
    val state by store.state.collectAsState()
    if (state.models.isEmpty()) return
    val theme = LocalMauriceTheme.current
    val domain = state.row(mauriceId)
    val current = state.resolvedModel(domain)
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(ProviderStyle.fill(current?.provider.orEmpty())))
            Spacer(Modifier.size(5.dp))
            Text(
                current?.name ?: "—", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.ink.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(Icons.Default.KeyboardArrowDown, L("chat.switch_model"), tint = theme.inkMute, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Providers in first-seen order, each a section.
            val providers = state.models.map { it.provider }.distinct()
            providers.forEachIndexed { i, provider ->
                if (i > 0) HorizontalDivider(color = theme.rule)
                Text(ProviderStyle.name(provider), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.8.sp, color = theme.inkMute, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                for (model in state.models.filter { it.provider == provider }) DropdownMenuItem(
                    text = { Text(model.name) },
                    trailingIcon = if (model.id == current?.id) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                    onClick = { open = false; store.setModel(model.id, domain) },
                )
            }
        }
    }
}
