package eu.chezmaurice.maurice.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.AccountEvent
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.platform.longDateShortTime
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.accessibilityLabel

/**
 * "November 22, 2026 at 9:12 AM — the host read your Maurice's logs
 * (requests and errors, not what was said). Reason: …" — a line of the
 * register of interventions, as `AccountEvent.sentence` on iOS. The date as
 * the system writes it. A kind this build does not know is told as a
 * device's; a line with no reason says so, but for a code the member asked
 * for.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
internal fun accountEventSentence(e: AccountEvent): String {
    val key = when (e.kind) {
        "household_export" -> "account_events.export"
        "device_at_request" -> "account_events.device_request"
        "operator_logs" -> "account_events.operator_logs"
        "operator_data" -> "account_events.operator_data"
        else -> "account_events.device"
    }
    val text = L(key, parseServerDate(e.createdAt)?.let { longDateShortTime(it.toEpochMilliseconds()) } ?: e.createdAt)
    val reason = e.reason?.takeIf { it.isNotEmpty() }
    return when {
        reason != null -> text + " " + L("account_events.reason", reason)
        e.kind != "device_at_request" -> text + " " + L("account_events.no_reason")
        else -> text
    }
}

/**
 * How many lines of the register the member has not read (`AccountEventsState`
 * on iOS): the orange dot on the settings cog and on Settings → My data. Read
 * when the app opens or comes back ([InterventionsWatch]); emptied when the
 * list is shown. A register, not an alarm: no dialog, no push (22 November 2026).
 */
class Interventions {
    var unread by mutableIntStateOf(0)
}

/** The signed-in member's register count; null outside the signed-in screens (no dot). */
val LocalInterventions = compositionLocalOf<Interventions?> { null }

/** The system orange iOS draws the dot in (`Color.orange`), light and dark. */
private val orangeLight = Color(0xFFFF9500)
private val orangeDark = Color(0xFFFF9F0A)

@Composable
internal fun interventionOrange(): Color = if (LocalMauriceTheme.current.isDark) orangeDark else orangeLight

/** The orange dot itself, drawn only while something in the register is unread. */
@Composable
fun InterventionDot(modifier: Modifier = Modifier, size: Dp = 7.dp) {
    if ((LocalInterventions.current?.unread ?: 0) <= 0) return
    val label = L("account_events.title")
    Box(modifier.size(size).clip(CircleShape).background(interventionOrange()).accessibilityLabel(label, button = false))
}

/**
 * Counts the unread lines of the register when the signed-in screens open
 * and each time [refresh] changes (the app back in front). Shows nothing
 * itself: the dialog it replaced read as an intrusion warning for what is,
 * most often, the member's own new phone. An unreadable answer keeps the
 * count as it was.
 */
@Composable
fun InterventionsWatch(session: Session, interventions: Interventions, refresh: Any?) {
    val api = remember(interventions) { session.api()?.let(::SettingsApi) } ?: return
    LaunchedEffect(interventions, refresh) {
        api.accountEvents()?.let { events -> interventions.unread = events.count { it.seenAt == null } }
    }
}
