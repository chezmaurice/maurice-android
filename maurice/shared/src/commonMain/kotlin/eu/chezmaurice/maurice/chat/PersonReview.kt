package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.PeopleApi
import eu.chezmaurice.core.chat.PersonRef
import eu.chezmaurice.core.chat.PersonReviewModel
import eu.chezmaurice.core.chat.withoutSources
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import kotlinx.coroutines.launch

/**
 * Opens a person fiche in the app: the "To check" card's rows, and the
 * footer's links in older replies. The second argument hears the fiche as it
 * stands after each gesture (null when it went), so the card updates its row.
 */
val LocalPersonReview = staticCompositionLocalOf<((PersonRef, (PersonReviewModel?) -> Unit) -> Unit)?> { null }

/** The conflict the mail pass records on an address, in the member's language (the server writes English). */
fun Strings.conflictText(c: String): String {
    Regex("^in (\\d+) cards of the address book$").find(c)?.let { m -> m.groupValues[1].toIntOrNull()?.let { return this("review.conflict.cards", it) } }
    Regex("^writes as « (.+) »$").find(c)?.let { m -> return this("review.conflict.writes_as", m.groupValues[1]) }
    return c
}

/**
 * The fiche — `PersonReviewView`: ✓ confirm, ✗ reject, ✎ correct, element by
 * element, and "confirm all". Links inside it open in the browser, signed in
 * ([gardenLinks]); the button at the top opens the fiche's own page there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonReviewSheet(
    ref: PersonRef,
    api: PeopleApi,
    /** `/g/<member>/<locale>/fiches/people/<basename>`, opened signed in. */
    webPath: String,
    gardenLinks: androidx.compose.ui.platform.UriHandler,
    onChange: (PersonReviewModel?) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var model by remember(ref.id) { mutableStateOf<PersonReviewModel?>(null) }
    var loadFailed by remember(ref.id) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var asking by remember { mutableStateOf<Asking?>(null) }

    LaunchedEffect(ref.id) {
        runCatching { api.load(ref) }
            .onSuccess { model = it; onChange(it) }
            .onFailure { loadFailed = true }
    }

    fun act(target: String, action: String, id: String?, text: String? = null) {
        scope.launch {
            working = true
            failed = null
            runCatching { api.review(ref, target, action, id, text) }
                .onSuccess { v ->
                    onChange(v)
                    if (v != null) model = v
                    // The fiche itself went (a person Maurice made, rejected).
                    else onDismiss()
                }
                .onFailure { failed = strings("review.failed") }
            working = false
        }
    }

    @Composable
    fun Markdown(text: String, sources: Boolean = false) {
        ReplyText(if (sources || showSources) text else withoutSources(text), serverUrl = "")
    }

    @Composable
    fun StatusChip(status: String) {
        val key = when (status) { "pending" -> "review.status.pending"; "rejected" -> "review.status.rejected"; else -> "review.status.confirmed" }
        val mark = when (status) { "pending" -> "?"; "rejected" -> "✕"; else -> "✓" }
        Text("$mark ${L(key)}", fontSize = 12.sp, color = if (status == "pending") Orange else theme.inkMute)
    }

    @Composable
    fun Gestures(status: String, target: String, id: String?, editText: String?, editTitle: String, reject: Pair<String, String>) {
        if (status == "pending") {
            Button(onClick = { act(target, "confirm", id) }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("✓ " + L("review.confirm"), fontSize = 13.sp) }
            OutlinedButton(onClick = { asking = Asking(target, id, reject.first, reject.second) }) { Text("✕ " + L("review.reject"), fontSize = 13.sp, color = theme.ink) }
        }
        if (editText != null && status != "rejected") {
            OutlinedButton(onClick = { editing = Editing(target, id, editTitle, editText) }) { Text("✎ " + L("review.edit"), fontSize = 13.sp, color = theme.ink) }
        }
    }

    /** One element: its text, its status, its gestures; a pending one carries a coloured edge. */
    @Composable
    fun Element(status: String, content: @Composable () -> Unit, actions: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(theme.inkMute.copy(alpha = if (status == "pending") 0.08f else 0.04f)),
        ) {
            if (status == "pending") Box(Modifier.padding(vertical = 8.dp).width(3.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Orange.copy(alpha = 0.7f)))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    StatusChip(status)
                    Spacer(Modifier.weight(1f))
                    actions()
                }
            }
        }
    }

    @Composable
    fun SectionTitle(key: String) {
        Text(L(key).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = theme.inkMute)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CompositionLocalProvider(LocalUriHandler provides gardenLinks) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
                // The bar: the browser, and Done.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { gardenLinks.openUri(webPath) }) { Text(L("review.open_garden"), color = theme.inkSoft, fontSize = 14.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink, fontWeight = FontWeight.SemiBold) }
                }
                val m = model
                when {
                    m != null -> Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).widthIn(max = 680.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        // Header: the name, who wrote it, what is left, confirm all, the sources.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(m.title, fontSize = 24.sp, fontFamily = FontFamily.Serif, color = theme.ink)
                            if (m.byMaurice) Text(L("review.by_maurice"), fontSize = 13.sp, color = theme.inkMute)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                if (m.pending > 0) {
                                    Text(if (m.pending == 1) L("review.one_item") else L("review.items", m.pending), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
                                    Button(onClick = { act("all", "confirm", null) }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(L("review.confirm_all"), fontSize = 13.sp, maxLines = 1) }
                                } else {
                                    Text("✓ " + L("review.all_checked"), fontSize = 13.sp, color = theme.inkMute)
                                }
                                if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkSoft)
                            }
                            Text(
                                "✉ " + L(if (showSources) "review.hide_sources" else "review.show_sources"), fontSize = 12.sp, color = theme.inkSoft,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showSources = !showSources }.padding(vertical = 4.dp),
                            )
                            failed?.let { Text(it, fontSize = 12.sp, color = Color.Red) }
                        }

                        // The relationship, and Maurice's revision of it beside it.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("review.relation")
                            val text = m.relation.text
                            if (!text.isNullOrEmpty()) {
                                val status = m.relation.status ?: "confirmed"
                                Element(status, content = {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Markdown(text)
                                        if (m.relation.since != null || m.relation.until != null) {
                                            Text("${m.relation.since ?: "?"} → ${m.relation.until ?: "…"}", fontSize = 12.sp, color = theme.inkMute)
                                        }
                                    }
                                }) {
                                    Gestures(status, "relation", null, text, strings("review.relation"),
                                        strings("review.reject_relation.title") to strings("review.reject_relation.message"))
                                }
                            } else {
                                Text(L("review.no_relation"), fontSize = 13.sp, color = theme.inkMute)
                            }
                            m.relation.proposed?.let { p ->
                                Element("pending", content = {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("⟳ " + L("review.proposed"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
                                        Markdown(p.text)
                                        if (p.since != null || p.until != null) Text("${p.since ?: "?"} → ${p.until ?: "…"}", fontSize = 12.sp, color = theme.inkMute)
                                    }
                                }) {
                                    Button(onClick = { act("proposal", "confirm", null) }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("✓ " + L("review.proposed.accept"), fontSize = 13.sp) }
                                    OutlinedButton(onClick = { act("proposal", "reject", null) }) { Text("✕ " + L("review.proposed.refuse"), fontSize = 13.sp, color = theme.ink) }
                                }
                            }
                        }

                        if (m.identities.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("review.addresses")
                            for (i in m.identities) {
                                Element(i.status, content = {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(i.address, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = theme.ink)
                                        if (i.mailboxes.isNotEmpty()) Text(L("review.mailboxes", i.mailboxes.joinToString(", ")), fontSize = 12.sp, color = theme.inkMute)
                                        i.conflict?.let { Text(strings.conflictText(it), fontSize = 12.sp, color = Orange) }
                                    }
                                }) {
                                    Gestures(i.status, "identity", i.address, null, "",
                                        strings("review.reject_address.title") to strings("review.reject_address.message"))
                                }
                            }
                        }

                        if (m.fragments.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("review.fragments")
                            for (f in m.fragments) {
                                Element(f.status, content = {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (f.summary.isNotEmpty()) Text(f.summary, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
                                        Markdown(f.body)
                                    }
                                }) {
                                    Gestures(f.status, "fragment", f.id, f.body, f.summary,
                                        strings("review.reject_fragment.title") to strings("review.reject_fragment.message"))
                                }
                            }
                        }

                        m.exchanges?.takeIf { it.isNotEmpty() }?.let { ex ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionTitle("review.exchanges")
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.inkMute.copy(alpha = 0.05f)).padding(12.dp)) { Markdown(ex, sources = true) }
                            }
                        }

                        if (m.byMaurice && m.status != "confirmed") {
                            OutlinedButton(onClick = {
                                asking = Asking("fiche", null, strings("review.reject_fiche.title"), strings("review.reject_fiche.message"))
                            }) { Text(L("review.not_person"), fontSize = 13.sp, color = Color.Red) }
                        }
                    }
                    loadFailed -> Text(L("review.load_failed"), color = theme.inkMute, modifier = Modifier.padding(20.dp))
                    else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = theme.inkSoft) }
                }
            }
        }
    }

    // A gesture that undoes something, asked first.
    asking?.let { a ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(a.title) },
            text = { Text(a.message) },
            confirmButton = { TextButton(onClick = { asking = null; act(a.target, "reject", a.id) }) { Text(L("review.reject"), color = Color.Red) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }

    // Correcting an element: the member's own words, in markdown; replacing Maurice's text confirms it.
    editing?.let { e ->
        var text by remember(e) { mutableStateOf(e.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.title.ifEmpty { L("review.edit") }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(L("review.edit.hint"), fontSize = 12.sp, color = theme.inkMute)
                    OutlinedTextField(text, { text = it }, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = theme.ink), modifier = Modifier.fillMaxWidth().height(220.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { editing = null; act(e.target, "edit", e.id, text) }) { Text(L("common.save")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }
}

private val Orange = Color(0xFFE08A1E)

private data class Editing(val target: String, val id: String?, val title: String, val text: String)

private data class Asking(val target: String, val id: String?, val title: String, val message: String)
