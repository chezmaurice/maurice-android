package eu.chezmaurice.maurice.domains

import eu.chezmaurice.ui.accessibilityLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.DomainsState
import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainProposal
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime

// One Maurice. A domain is a part of the member's life he follows, with a
// brief he keeps on it; a reading companion is a book followed at the reading
// position, entered as a pinned conversation (`DomainsViews.swift`).

/** Which domain sheet is open: the list, a domain's page, the editor. */
@Stable
class DomainsNav {
    var showList by mutableStateOf(false)
    /** The domain whose page (brief) is open. */
    var briefFor by mutableStateOf<Domain?>(null)
    /** The domain being edited, or a blank one being created; null = closed. */
    var editing by mutableStateOf<Domain?>(null)

    fun openList() { showList = true }
    fun openBrief(d: Domain) { showList = false; briefFor = d }
    fun openEditor(d: Domain?) { showList = false; briefFor = null; editing = d ?: BLANK }
    /** Out of the editor, back to the list it came from. */
    fun backToList() { editing = null; showList = true }

    companion object {
        /** The editor's "new" state: no id yet. */
        val BLANK = Domain(id = "", name = "")
    }
}

val LocalDomains = staticCompositionLocalOf<DomainStore?> { null }
val LocalDomainsNav = staticCompositionLocalOf { DomainsNav() }

/** The member's colour: what a domain's mark wears. */
val LocalAccent = staticCompositionLocalOf { Color(0xFF2C5AA0) }

/** The glyph for a row: open pages for a companion, else its icon, else a closed book. */
fun Domain.symbol(): String = when {
    isCompanion -> "book.pages"
    DomainGlyphs.has(icon) -> icon!!
    else -> DomainGlyphs.FALLBACK
}

/**
 * What stands where a hat used to: the domain's glyph on the member's accent,
 * in a rounded square (a circle with [radius] = size / 2).
 */
@Composable
fun DomainMark(domain: Domain, size: Dp = 30.dp, radius: Dp = size * 0.28f) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier.size(size).clip(shape).background(accent.copy(alpha = 0.14f)).border(0.5.dp, theme.ruleHard, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(DomainGlyphs.of(domain.symbol()), null, tint = legible(accent, theme.isDark), modifier = Modifier.size(size * 0.5f))
    }
}

/** A small mono pill, as in greetings and previews. */
@Composable
fun InfoPill(symbol: String, text: String) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DomainGlyphs.of(symbol), null, tint = theme.inkSoft, modifier = Modifier.size(11.dp))
        Spacer(Modifier.size(5.dp))
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkSoft)
    }
}

/** A capsule action: an outlined pill with a glyph. */
@Composable
fun PillButton(symbol: String?, label: String, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val color = tint ?: theme.inkSoft
    Row(
        Modifier.clip(CircleShape).border(0.5.dp, tint?.copy(alpha = 0.5f) ?: theme.ruleHard, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) {
            Icon(DomainGlyphs.of(symbol), null, tint = color.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

/** Mono uppercase kicker over a section. */
@Composable
fun SectionHead(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.1.sp,
        color = LocalMauriceTheme.current.inkMute, modifier = modifier.padding(horizontal = 4.dp).padding(bottom = 2.dp),
    )
}

/** The warm red of erasing and putting away. */
val ERASE = Color(0xFFA6452E)

/** 0…1 temperature as the design's word. */
@Composable
fun creativityLabel(temp: Double): String = when {
    temp <= 0.3 -> L("persona.creativity.precise")
    temp >= 0.75 -> L("persona.creativity.creative")
    else -> L("persona.creativity.balanced")
}

@Composable
fun reasoningLabel(thinking: Boolean?): String = when (thinking) {
    true -> L("persona.reasoning.on")
    false -> L("persona.reasoning.off")
    null -> L("persona.reasoning.auto")
}

/** `12 345` → `12.3k`, as the composer counts tokens. */
fun fmtTok(n: Int): String {
    if (n < 1000) return n.toString()
    val k = n / 1000.0
    // Swift's `rounded()`: half away from zero (Kotlin's `round` goes to even).
    if (k >= 100) return "${k.roundToLong()}k"
    val tenths = tenthsAsPrintf(n)
    return "${tenths / 10}.${tenths % 10}k"
}

/**
 * n / 1000 in tenths, rounded as `String(format: "%.1f")` rounds the double:
 * by its exact binary value, an exact tie to even. 1050 is 1.0500000000000000444
 * as a double, so "1.1"; 2250 is exactly 2.25, so "2.2".
 */
private fun tenthsAsPrintf(n: Int): Long {
    val a = n / 100L          // whole tenths
    val rest = n % 100        // thousandths beyond them
    if (rest != 50) return if (rest > 50) a + 1 else a
    // A tie in decimal: which side of (2a + 1) / 20 does the double fall on?
    val bits = (n / 1000.0).toRawBits()
    val exponent = ((bits shr 52) and 0x7ff).toInt() - 1075          // value = mantissa * 2^exponent
    val mantissa = (bits and 0xfffffffffffffL) or (1L shl 52)
    val lhs = 20 * mantissa                                           // value * 20 * 2^-exponent
    val rhs = (2 * a + 1) shl -exponent
    return when {
        lhs > rhs -> a + 1
        lhs < rhs -> a
        else -> if (a % 2 == 0L) a else a + 1
    }
}

/**
 * A day as iOS writes `formatted(date: .abbreviated, time: .omitted)` in
 * [language], in the device's zone: "Nov 10, 2026", "10 nov. 2026".
 */
@OptIn(ExperimentalTime::class)
fun abbreviatedDay(raw: String?, language: String): String? {
    val d = parseServerDate(raw)?.toLocalDateTime(TimeZone.currentSystemDefault())?.date ?: return null
    val month = (SHORT_MONTHS[language] ?: SHORT_MONTHS.getValue("en"))[d.month.ordinal]
    return when (language) {
        "fr", "es", "it", "nl" -> "${d.day} $month ${d.year}"
        "de" -> "${d.day}. $month ${d.year}"
        "pt" -> "${d.day} de $month de ${d.year}"
        else -> "$month ${d.day}, ${d.year}"
    }
}

/** CLDR's abbreviated month names (format context), as Foundation uses them. */
private val SHORT_MONTHS = mapOf(
    "en" to listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
    "fr" to listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."),
    "de" to listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez."),
    "es" to listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic"),
    "it" to listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic"),
    "pt" to listOf("jan.", "fev.", "mar.", "abr.", "mai.", "jun.", "jul.", "ago.", "set.", "out.", "nov.", "dez."),
    "nl" to listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"),
)

/** "today", "3d", "2w", "4mo", "1y" — how long ago a server date was. */
@OptIn(ExperimentalTime::class)
fun ageOf(raw: String?, strings: Strings): String {
    val then = parseServerDate(raw) ?: return ""
    val days = ((Clock.System.now() - then).inWholeDays).toInt()
    return when {
        days < 1 -> strings("gardens.today")
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}

/** "12 conversations · 30 mail threads · 4 recent · quiet since 2026-03 · Oct 21, 2026", in [language]. */
fun proposalNumbers(p: DomainProposal, strings: Strings, language: String): String {
    val bits = mutableListOf<String>()
    val mail = p.mailThreads ?: 0
    if (p.conversations > 0 || mail == 0) bits += strings(if (p.conversations == 1) "proposals.conversations.one" else "proposals.conversations.other", p.conversations)
    if (mail > 0) bits += strings(if (mail == 1) "proposals.mail.one" else "proposals.mail.other", mail)
    p.recent90Days?.takeIf { it > 0 }?.let { bits += strings("proposals.recent", it) }
    if (!p.isAlive) p.to?.let { bits += strings("proposals.quiet_since", it.take(7)) }
    abbreviatedDay(p.createdAt, language)?.let { bits += it }
    return bits.joinToString(" · ")
}

// ── The greeting ───────────────────────────────────────────

/**
 * The empty conversation of a domain or a companion: its mark, name and
 * tagline, the model / context / creativity pills, the way to its page and
 * to the editor (`DomainGreeting`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DomainGreeting(domain: Domain, memberId: String, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    val store = LocalDomains.current
    val nav = LocalDomainsNav.current
    val state = store?.state?.collectAsState()?.value ?: DomainsState()
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DomainMark(domain, size = 72.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L(if (domain.isCompanion) "domains.kicker.companion" else "domains.kicker.domain"), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = theme.inkMute)
            Text(domain.name, style = wordmark(28.sp), color = theme.ink, textAlign = TextAlign.Center)
        }
        if (domain.tagline.isNotBlank()) {
            Text(domain.tagline, fontSize = 14.sp, color = theme.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val model = state.resolvedModel(domain)
            InfoPill(if (model?.isLocal == true) "lock.shield" else "cloud", model?.name ?: "—")
            if (domain.count > 0) {
                InfoPill("doc.text", L(if (domain.count == 1) "persona.preview.sources.one" else "persona.preview.sources.other", domain.count, fmtTok(domain.weight)))
            }
            InfoPill("dial.medium", creativityLabel(domain.temp))
            val thinking = domain.thinking
            if (thinking != null && state.model(domain.model)?.thinkingIsOptional == true) {
                InfoPill(if (thinking) "brain" else "bolt", reasoningLabel(thinking))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (domain.isDomainOf(memberId)) PillButton("book.closed", L("brief.open")) { nav.openBrief(domain) }
            if (domain.createdBy == memberId) PillButton("pencil.and.outline", L("domain.edit")) { nav.openEditor(domain) }
        }
    }
}

// ── The pastilles ──────────────────────────────────────────

/** One mark beside the title, and why it is there. */
data class Pastille(val domain: Domain, val kind: Kind) {
    enum class Kind { Bound, BoundAuto, Read, Recognised }
}

/**
 * The member's own domains this conversation has to do with, in the order
 * they came in: the one it is bound to, each whose brief Maurice read on the
 * way (`domain_brief`), and the one the server last recognised in what the
 * member said (`domain_recognised`).
 */
fun pastilles(
    domains: List<Domain>,
    memberId: String,
    boundId: String?,
    boundByNight: Boolean,
    blocks: List<DataBlock>,
): List<Pastille> {
    val own = domains.filter { it.isDomainOf(memberId) }
    if (own.isEmpty()) return emptyList()
    val out = mutableListOf<Pastille>()
    fun add(d: Domain?, kind: Pastille.Kind) {
        if (d != null && out.none { it.domain.id == d.id }) out += Pastille(d, kind)
    }
    fun domainOf(block: DataBlock): Domain? {
        val data = block.data as? JsonObject ?: return null
        val id = (data["domain_id"] as? JsonPrimitive)?.content
        val name = (data["domain"] as? JsonPrimitive)?.content
        // Replies older than the id in the payload carry the name only.
        return own.firstOrNull { it.id == id } ?: own.firstOrNull { it.name == name }
    }
    if (boundId != null) add(own.firstOrNull { it.id == boundId }, if (boundByNight) Pastille.Kind.BoundAuto else Pastille.Kind.Bound)
    for (b in blocks) if (b.tool == "domain_brief") add(domainOf(b), Pastille.Kind.Read)
    blocks.lastOrNull { it.tool == "domain_recognised" }?.let { add(domainOf(it), Pastille.Kind.Recognised) }
    return out
}

/**
 * The pastilles as round marks; a tap opens a short menu: the domain's page,
 * and binding the conversation to it or taking it out. More than [max] fold
 * into "+N".
 */
@Composable
fun DomainPastilles(list: List<Pastille>, controller: ChatController, canBind: Boolean, size: Dp = 28.dp, max: Int = 3) {
    if (list.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (p in list.take(max)) PastilleButton(p, controller, canBind, size) { PastilleMark(p, size) }
        val rest = list.drop(max)
        if (rest.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            var chosen by remember { mutableStateOf<Pastille?>(null) }
            Box {
                Box(Modifier.sizeIn(minWidth = size, minHeight = size).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
                    Text("+${rest.size}", fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Medium, color = LocalMauriceTheme.current.inkSoft)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (p in rest) DropdownMenuItem(
                        text = { Text(p.domain.name) },
                        leadingIcon = { Icon(DomainGlyphs.of(p.domain.symbol()), null, Modifier.size(18.dp)) },
                        onClick = { open = false; chosen = p },
                    )
                }
                chosen?.let { p -> PastilleMenu(p, controller, canBind, expanded = true) { chosen = null } }
            }
        }
    }
}

@Composable
private fun PastilleButton(p: Pastille, controller: ChatController, canBind: Boolean, size: Dp, mark: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(size).clip(CircleShape).clickable { open = true }.accessibilityLabel(pastilleLabel(p))) { mark() }
        PastilleMenu(p, controller, canBind, open) { open = false }
    }
}

@Composable
private fun PastilleMenu(p: Pastille, controller: ChatController, canBind: Boolean, expanded: Boolean, onDismiss: () -> Unit) {
    val nav = LocalDomainsNav.current
    val scope = rememberCoroutineScope()
    val theme = LocalMauriceTheme.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(pastilleLabel(p), fontSize = 12.sp, color = theme.inkMute, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        DropdownMenuItem(
            text = { Text(L("pastille.open")) },
            leadingIcon = { Icon(DomainGlyphs.of("book.closed"), null, Modifier.size(18.dp)) },
            onClick = { onDismiss(); nav.openBrief(p.domain) },
        )
        // Binding is the member's own conversation's business, not a room's.
        if (canBind) {
            val bound = p.kind == Pastille.Kind.Bound || p.kind == Pastille.Kind.BoundAuto
            DropdownMenuItem(
                text = { Text(L(if (bound) "pastille.detach" else "pastille.attach"), color = if (bound) MaterialTheme.colorScheme.error else Color.Unspecified) },
                leadingIcon = { Icon(DomainGlyphs.of(if (bound) "link.badge.minus" else "link.badge.plus"), null, Modifier.size(18.dp)) },
                onClick = { onDismiss(); scope.launch { controller.setDomain(if (bound) null else p.domain.id) } },
            )
        }
    }
}

@Composable
private fun pastilleLabel(p: Pastille): String = when (p.kind) {
    Pastille.Kind.Bound -> L("pastille.bound", p.domain.name)
    Pastille.Kind.BoundAuto -> L("pastille.bound.auto", p.domain.name)
    Pastille.Kind.Read -> L("pastille.read", p.domain.name)
    Pastille.Kind.Recognised -> L("pastille.recognised", p.domain.name)
}

/** Full for a bound domain and a brief read; a dashed ring and a paler glyph for one only recognised. */
@Composable
private fun PastilleMark(p: Pastille, size: Dp) {
    if (p.kind != Pastille.Kind.Recognised) return DomainMark(p.domain, size, radius = size / 2)
    val theme = LocalMauriceTheme.current
    val ink = legible(LocalAccent.current, theme.isDark)
    Box(
        Modifier.size(size).drawBehind {
            val w = 1.dp.toPx()
            drawRoundRect(
                ink.copy(alpha = 0.7f), cornerRadius = CornerRadius(this.size.minDimension / 2),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))),
                topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(DomainGlyphs.of(p.domain.symbol()), null, tint = ink.copy(alpha = 0.75f), modifier = Modifier.size(size * 0.5f))
    }
}

/** The strings at hand outside a composable call site's L(). */
@Composable
fun strings(): Strings = LocalStrings.current

/** Spacer of a given height, for sheets. */
@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

/** A thin dashed outline: a proposal's dots, the "new domain" row. */
fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val w = 0.5.dp.toPx()
    drawRoundRect(
        color, cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
    )
}
