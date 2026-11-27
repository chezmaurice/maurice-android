package eu.chezmaurice.maurice.budget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.budget.BudgetStore
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.settings.IndexRow
import eu.chezmaurice.maurice.settings.SetCaption
import eu.chezmaurice.maurice.settings.SetCard
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/** The household's budget, for the signed-in member; null outside the home screen. */
val LocalBudget = staticCompositionLocalOf<BudgetStore?> { null }

/** Asks the home screen for Settings, opened on Budget: the ring and the toast lead there. */
val LocalOpenBudget = staticCompositionLocalOf<() -> Unit> { {} }

private val WARM = Color(0xFFE08A1E)
private val RED = Color(0xFFD7372F)

/**
 * The household's budget as a ring: full when it was last filled, shrinking
 * as it is spent; full with ∞ in the middle when there is no budget. Warm
 * below 20 %, red below 5 %, the thresholds the toasts speak at.
 */
@Composable
fun BudgetRing(left: Double?, size: Dp = 22.dp, showsPercent: Boolean = false) {
    val theme = LocalMauriceTheme.current
    val accent = legible(LocalAccent.current, theme.isDark)
    val tint = when {
        left == null -> accent
        left <= 0.05 -> RED
        left <= 0.20 -> WARM
        else -> accent
    }
    val sweep by animateFloatAsState((left ?: 1.0).coerceIn(0.0, 1.0).toFloat(), tween(500))
    val line = (size.value * if (showsPercent) 0.08f else 0.14f).coerceAtLeast(2.5f).dp
    Box(Modifier.size(size).padding(line / 2), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = line.toPx()
            drawCircle(theme.rule, style = Stroke(stroke))
            drawArc(tint, startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        when {
            left == null -> Text("∞", fontSize = (size.value * if (showsPercent) 0.3f else 0.42f).sp, fontWeight = FontWeight.SemiBold, color = theme.inkSoft)
            showsPercent -> Text("${(left * 100).roundToInt()} %", fontSize = (size.value * 0.2f).sp, fontWeight = FontWeight.Medium, color = theme.ink)
        }
    }
}

/** The toast raised when the budget falls to 20, 10 or 5 %: a tap opens Settings → Budget; it goes away on its own. */
@Composable
fun BudgetToast(store: BudgetStore, percent: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    LaunchedEffect(percent) {
        delay(8_000)
        if (store.state.value.alert == percent) store.dismissAlert()
    }
    Row(
        modifier.shadow(10.dp, CircleShape).clip(CircleShape).background(theme.surface).border(0.5.dp, theme.rule, CircleShape)
            .clickable { store.dismissAlert(); onOpen() }
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BudgetRing(percent / 100.0, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(L("budget.toast.title", percent), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink)
        Spacer(Modifier.width(10.dp))
        Icon(DomainGlyphs.of("chevron.right"), null, tint = theme.inkMute, modifier = Modifier.size(11.dp))
    }
}

/** "84 % left", or "No limit": the value beside the ring in Settings. */
@Composable
fun budgetValue(left: Double?): String =
    if (left == null) L("budget.value.unlimited") else L("budget.value.left", (left * 100).roundToInt())

/**
 * Settings → Budget: the ring, large, what is left in words, when a monthly
 * budget starts afresh or that a prepaid one is looked after by whoever runs
 * the household. The same for every member and never an amount, except to
 * the admin. No way to pay here.
 */
@Composable
fun BudgetPane(store: BudgetStore, languageTag: String, openSubscription: (() -> Unit)? = null) {
    val theme = LocalMauriceTheme.current
    val state by store.state.collectAsState()
    LaunchedEffect(store) { store.refresh() }
    val left = state.left
    val budget = state.budget
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BudgetRing(left, size = 132.dp, showsPercent = true)
    }
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(theme.surfaceAlt)
            .border(0.5.dp, theme.rule, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (left == null) L("budget.pane.unlimited") else L("budget.pane.left", (left * 100).roundToInt()),
            fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink,
        )
        if (left != null) budget?.leftEur?.let { eur ->
            Text(L("budget.pane.admin_left", formatEuros(eur, languageTag)), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink)
        }
        if (left != null && budget != null) {
            val detail = when {
                budget.kind == "balance" -> L("budget.pane.balance")
                else -> budget.resetsAt?.let { longDate(it, languageTag) }?.let { L("budget.pane.resets", it) }
            }
            detail?.let { Text(it, fontSize = 12.5.sp, color = theme.inkSoft) }
        }
    }
    // The admin who can top up goes on to Abonnement; a member sees nothing of it.
    if (budget?.canTopUp == true && openSubscription != null) SetCard {
        IndexRow("plus.circle", L("store.topup.open"), onClick = openSubscription)
    }
    SetCaption(L("budget.caption"))
}

/** "12,40 €" or "€12.40", as the member's language writes it. */
private fun formatEuros(amount: Double, language: String): String {
    val cents = (amount * 100).roundToInt()
    val units = cents / 100
    val rest = (cents % 100).toString().padStart(2, '0')
    return if (language == "en") "€$units.$rest" else "$units,$rest €"
}

/**
 * A server date's day, in UTC, written out in [language]: "1 December 2026".
 * Takes ISO 8601 and SQLite's "2026-10-29 17:23:50" (UTC, without a zone).
 */
fun longDate(serverDate: String, language: String): String? {
    val iso = serverDate.trim().replace(' ', 'T').let { if (it.length == 19) it + "Z" else it }
    val date: LocalDate = runCatching { Instant.parse(iso).toLocalDateTime(TimeZone.UTC).date }.getOrNull() ?: return null
    val month = monthName(date.month.ordinal, language)
    return when (language) {
        "en" -> "$month ${date.day}, ${date.year}"
        "de" -> "${date.day}. $month ${date.year}"
        "es", "pt" -> "${date.day} de $month de ${date.year}"
        else -> "${date.day} $month ${date.year}"
    }
}

/** A month's name in [language] (0 = January), as it stands alone: "octobre". English for a language not held. */
fun monthName(index: Int, language: String): String =
    MONTHS[language]?.getOrNull(index) ?: MONTHS.getValue("en")[index]

private val MONTHS = mapOf(
    "en" to listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
    "fr" to listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"),
    "it" to listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"),
    "de" to listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"),
    "es" to listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"),
    "pt" to listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"),
    "nl" to listOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december"),
)
