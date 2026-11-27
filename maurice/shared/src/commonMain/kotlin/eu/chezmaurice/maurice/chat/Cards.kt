package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import eu.chezmaurice.core.chat.PersonRef
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.chezmaurice.core.chat.ToolStep
import eu.chezmaurice.core.chat.TurnSources
import eu.chezmaurice.core.chat.TurnTally
import eu.chezmaurice.core.chat.array
import eu.chezmaurice.core.chat.bool
import eu.chezmaurice.core.chat.candidateIdToken
import eu.chezmaurice.core.chat.cardKind
import eu.chezmaurice.core.chat.int
import eu.chezmaurice.core.chat.str
import eu.chezmaurice.core.chat.strings
import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.MauriceIcons
import eu.chezmaurice.ui.Strings
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** "2 web searches, 1 memory search" — `TurnTally.text`. */
/**
 * What a member sees for a tool — `ToolStep.label(for:running:)`: the web
 * search and the page read have words of their own; an MCP tool, namespaced
 * "server__tool", is named by its server ("garden", "library").
 */
fun Strings.toolLabel(tool: String, running: Boolean): String = when (tool) {
    "web_search" -> this(if (running) "chat.activity.webSearch" else "chat.activity.webSearchDone")
    "read_web_page" -> this(if (running) "chat.activity.readPage" else "chat.activity.readPageDone")
    else -> this(if (running) "chat.activity.usingTool" else "chat.activity.usedTool", tool.substringBefore("__"))
}

/** The label, with the count when it ran more than once — `ToolStep.name`. */
fun Strings.toolName(step: ToolStep): String =
    toolLabel(step.tool, step.running).let { if (step.count > 1) "$it ×${step.count}" else it }

fun Strings.tally(t: TurnTally): String = listOf("web" to t.web, "memory" to t.memory, "tools" to t.tools)
    .filter { it.second > 0 }
    .joinToString(", ") { (kind, n) -> this("chat.tally.$kind.${if (n == 1) "one" else "other"}", n) }

/**
 * What a reply took — `TurnActivityRow`: the first sources found, the tally,
 * and while the turn runs, the tool at work. Opens the drawer.
 */
@Composable
fun TurnSummaryLine(steps: List<ToolStep>, blocks: List<DataBlock>, serverUrl: String, running: Boolean, now: String? = null) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val tally = TurnTally.of(steps, blocks, doneOnly = running)
    var open by remember { mutableStateOf(false) }
    if (tally.isEmpty && now == null) return
    Row(
        Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp))
            .clickable(enabled = blocks.isNotEmpty() || steps.isNotEmpty()) { open = true }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SourceStack(blocks, serverUrl)
        if (!tally.isEmpty) Text(strings.tally(tally), fontSize = 12.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (now != null) {
            if (!tally.isEmpty) Text("–", fontSize = 12.5.sp, color = theme.inkMute)
            Text(now, fontSize = 12.5.sp, color = theme.inkSoft, maxLines = 1)
        }
        if (running) ThinkingPulse(color = theme.inkSoft)
        if (!running) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.size(14.dp))
    }
    if (open) TurnDrawer(steps, blocks, serverUrl) { open = false }
}

/** The first few sources, overlapping, as pills. */
@Composable
private fun SourceStack(blocks: List<DataBlock>, serverUrl: String) {
    val leading = TurnSources.leading(blocks)
    if (leading.isEmpty()) return
    Box(Modifier.width((20 + (leading.size - 1) * 12).dp)) {
        leading.forEachIndexed { i, (item, isWeb) ->
            SourceThumb(item, isWeb, serverUrl, 20.dp, Modifier.offset(x = (i * 12).dp))
        }
    }
}

/** A cover when there is one, else the site's initial (web) or the kind's first letter. */
@Composable
private fun SourceThumb(item: JsonElement, isWeb: Boolean, serverUrl: String, side: Dp, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    val image = absoluteUrl(serverUrl, item.str("image").ifEmpty { null })
    Box(
        modifier.size(side).clip(RoundedCornerShape(side / 4)).background(theme.surfaceAlt),
        contentAlignment = Alignment.Center,
    ) {
        val letter = if (isWeb) siteName(item.str("url")).take(1) else item.str("kind").take(1)
        Text(letter.uppercase(), fontSize = (side.value * 0.45f).sp, color = theme.inkSoft, fontWeight = FontWeight.SemiBold)
        if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(side))
    }
}

private fun siteName(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

/** The record of a turn: what it searched, place by place, and the other tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TurnDrawer(steps: List<ToolStep>, blocks: List<DataBlock>, serverUrl: String, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(L("chat.tally.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
            for (group in TurnSources.byOrigin(blocks)) {
                val key = if (group.isWeb) "sources.web" else "sources.corpus"
                val results = group.results
                Text(
                    if (group.total > results.size) strings("sources.more", strings(key, results.size), group.total) else strings(key, group.total),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.ink,
                )
                for (q in group.queries) Text("« $q »", fontSize = 13.sp, color = theme.inkMute)
                for (item in results) {
                    val url = item.str("url")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.inkMute.copy(alpha = 0.06f))
                            .clickable(enabled = url.isNotEmpty()) { uri.openUri(url) }.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SourceThumb(item, group.isWeb, serverUrl, 44.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.str("title"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val subtitle = if (group.isWeb) item.str("subtitle").ifEmpty { siteName(url) } else {
                                val kind = strings("sources.kind.${item.str("kind")}")
                                listOf(kind, item.str("subtitle")).filter { it.isNotEmpty() }.joinToString(" · ")
                            }
                            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 11.sp, color = theme.inkMute)
                            val snippet = item.str("snippet")
                            if (snippet.isNotEmpty()) Text(snippet, fontSize = 12.sp, color = theme.inkSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val others = blocks.filter { it.cardKind == null }
            val toolNames = (steps.map { it.tool } + others.map { it.tool }).filter { it != "web_search" && it != "corpus__search" }.distinct()
            if (toolNames.isNotEmpty()) {
                HorizontalDivider(color = theme.rule)
                Text(L("chat.tally.toolsHeader"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                for (tool in toolNames) {
                    val count = steps.firstOrNull { it.tool == tool }?.count ?: others.count { it.tool == tool }
                    Text("${tool.replace("__", " · ")}${if (count > 1) " ×$count" else ""}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = theme.inkSoft)
                }
            }
        }
    }
}

/** The cards drawn under a reply, as part of the answer — `DataCardStack`. */
@Composable
fun AnswerCards(
    blocks: List<DataBlock>,
    serverUrl: String,
    accent: Color,
    onSend: (String) -> Unit,
    decideFact: suspend (String, Boolean) -> String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        for (block in blocks) when (block.cardKind) {
            "candidates" -> CandidatesCard(block.data, serverUrl, onSend)
            "media" -> MediaCard(block.data, serverUrl, accent)
            "fact" -> FactCard(block.data, accent, decideFact)
            "review" -> ReviewCard(block.data)
        }
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.inkMute.copy(alpha = 0.06f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun CardThumb(path: String, serverUrl: String, width: Dp = 40.dp) {
    val theme = LocalMauriceTheme.current
    val url = absoluteUrl(serverUrl, path.ifEmpty { null })
    Box(Modifier.size(width, width * 1.5f).clip(RoundedCornerShape(4.dp)).background(theme.surfaceAlt)) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(width, width * 1.5f))
    }
}

/** "Which one?" — tapping one sends the message that opens its fiche. */
@Composable
private fun CandidatesCard(data: JsonElement, serverUrl: String, onSend: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val collection = data.str("collection")
    CardBox {
        Text(L("fiche.pick_title"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.inkMute)
        for (row in data.array("results").take(8)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                    onSend(strings("fiche.open_prefill", row.str("title"), collection, candidateIdToken(collection, row)))
                }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CardThumb(row.str("image"), serverUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.str("title"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                        row.int("year")?.let { Text(it.toString(), fontSize = 12.sp, color = theme.inkMute) }
                    }
                    row.str("subtitle").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = theme.inkSoft) }
                    row.str("summary").takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = theme.inkMute, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** The fiche as a working surface: what this is, and how far each face has got. */
@Composable
private fun MediaCard(data: JsonElement, serverUrl: String, accent: Color) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val uri = LocalUriHandler.current
    val facts = buildList {
        data.int("year")?.let { add(it.toString()) }
        when (data.str("collection")) {
            "movies" -> add(data.str("director"))
            "series" -> {
                add(data.str("platform"))
                data.int("seasons")?.takeIf { it > 0 }?.let { add(strings("fiche.seasons", it)) }
            }
            "books" -> add(data.str("author"))
            "podcasts" -> add(data.str("host"))
            "music" -> add(data.str("artist"))
            "games" -> { add(data.str("developer")); add(data.strings("platforms").joinToString(", ")) }
        }
    }.filter { it.isNotEmpty() }
    val state = data.str("card_state")
    CardBox {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardThumb(data.str("image"), serverUrl, 58.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(data.str("title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 12.sp, color = theme.inkSoft)
                Text(
                    L(when (state) { "published" -> "fiche.state_published"; "draft" -> "fiche.state_draft"; else -> "fiche.state_absent" }),
                    fontSize = 11.sp, color = when (state) { "published" -> theme.ink; "draft" -> theme.inkSoft; else -> theme.inkMute },
                )
                Text(L("fiche.counts", data.int("recto_chars") ?: 0, data.int("verso_chars") ?: 0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = theme.inkMute)
                if (data.bool("fiche_created")) Text(L("fiche.created"), fontSize = 11.sp, color = theme.inkMute)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for ((label, path) in listOf("fiche.open_fiche" to data.str("fiche_url"), "fiche.open_card" to data.str("card_url"))) {
                        if (path.isEmpty()) continue
                        Row(Modifier.clickable { uri.openUri(path) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(MauriceIcons.OpenInNew, null, tint = accent, modifier = Modifier.size(11.dp))
                            Text(L(label), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = accent)
                        }
                    }
                }
            }
        }
    }
}

/** "Maurice noted this about you": keep it, or let it go. */
@Composable
private fun FactCard(data: JsonElement, accent: Color, decide: suspend (String, Boolean) -> String?) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    var decision by remember { mutableStateOf(data.str("state").takeIf { it == "kept" || it == "dismissed" }) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun choose(keep: Boolean) {
        working = true; failed = false
        scope.launch {
            val state = decide(data.str("id"), keep)
            if (state == null) failed = true else decision = state
            working = false
        }
    }
    CardBox {
        Text(L("fact.heading"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
        Text(data.str("text"), fontSize = 14.sp, color = theme.ink)
        when (decision) {
            "kept" -> Text("✓ " + L("fact.kept"), fontSize = 12.sp, color = theme.inkMute)
            "dismissed" -> Text("✕ " + L("fact.dismissed"), fontSize = 12.sp, color = theme.inkMute)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { choose(true) }, enabled = !working, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(L("fact.keep"), fontSize = 13.sp) }
                OutlinedButton(onClick = { choose(false) }, enabled = !working) { Text(L("fact.dismiss"), fontSize = 13.sp, color = theme.inkSoft) }
                if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute)
            }
        }
        if (failed) Text(L("fact.failed"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * "To check" — `ReviewCard`: what the reply rested on that the member has
 * not confirmed yet, one row per person fiche; a tap opens the fiche in the
 * app ([LocalPersonReview]), and the row then says what is left.
 */
@Composable
private fun ReviewCard(data: JsonElement) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val review = LocalPersonReview.current
    // What is still pending per fiche, once the member has been in it.
    var left by remember { mutableStateOf(mapOf<String, Int>()) }
    CardBox {
        Text(L("review.heading"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
        for (fiche in data.array("fiches")) {
            val locale = fiche.str("locale")
            val basename = fiche.str("basename")
            if (locale.isEmpty() || basename.isEmpty()) continue
            val ref = PersonRef(locale, basename)
            val labels = fiche.array("items").map { it.str("label") }
            val remaining = left[ref.id] ?: labels.size
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = review != null) { review?.invoke(ref) { m -> left = left + (ref.id to (m?.pending ?: 0)) } }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (remaining == 0) Icons.Default.CheckCircle else Icons.Default.Person, null, tint = if (remaining == 0) theme.inkMute else theme.inkSoft, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(fiche.str("title"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                    Text(
                        when {
                            remaining == 0 -> strings("review.all_checked")
                            left[ref.id] == null && labels.size == 1 -> labels[0]
                            remaining == 1 -> strings("review.one_item")
                            else -> strings("review.items", remaining)
                        },
                        fontSize = 12.sp, color = theme.inkMute, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.size(16.dp))
            }
        }
    }
}
