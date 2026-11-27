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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.composer.ComposerStore
import eu.chezmaurice.core.composer.TrayItem
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.SaveOutcome
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.ModelInfo
import eu.chezmaurice.core.consent.AiConsentStore
import eu.chezmaurice.maurice.consent.AiProviderAskDialog
import eu.chezmaurice.maurice.consent.LocalAiConsent
import eu.chezmaurice.maurice.chat.BackHandlerCompat
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.composer.AddContextSheet
import eu.chezmaurice.maurice.composer.ContextTray
import eu.chezmaurice.maurice.composer.ToolFamilyPicker
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val PROVIDER_ORDER = listOf("anthropic", "openai", "mistral", "zai", "scaleway", "ollama")

/**
 * Add or edit a domain or a reading companion (`DomainEditor`): a live
 * preview, then its name, tagline and icon, its kind, its model, what it is
 * about and how freely it answers, its baked-in context, its tools.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DomainEditor(initial: Domain, store: DomainStore, controller: ChatController, newContext: (() -> ComposerStore)?, onClose: () -> Unit, onBack: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val strings = strings()
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    val isEdit = initial.id.isNotEmpty()
    // A fresh domain starts with the garden's notes (the web is always on).
    var draft by remember { mutableStateOf(initial.copy(toolFamilies = initial.toolFamilies ?: listOf("garden-notes"))) }
    val context = remember { newContext?.invoke() }
    val ctx = context?.state?.collectAsState()?.value
    LaunchedEffect(Unit) { context?.seed(initial.context.mapNotNull(TrayItem::fromSpec)) }
    var showIcons by remember { mutableStateOf(false) }
    var addingContext by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canSave = draft.name.isNotBlank() && !saving
    val selectedModel = state.model(draft.model)
    // A model whose provider the member has not accepted yet: asked first,
    // and chosen only on a yes.
    val aiConsent = LocalAiConsent.current
    var asking by remember { mutableStateOf<ModelInfo?>(null) }
    fun chooseModel(m: ModelInfo) {
        val consent = aiConsent?.state?.value
        if (consent == null || !consent.loaded || m.isLocal || consent.covers(m.provider)) draft = draft.copy(model = m.id)
        else asking = m
    }
    asking?.let { m ->
        val provider = aiConsent?.state?.value?.providers?.firstOrNull { it.id == m.provider } ?: AiConsentStore.knownProvider(m.provider)
        if (aiConsent != null) AiProviderAskDialog(aiConsent, provider) { ok ->
            if (ok) draft = draft.copy(model = m.id)
            asking = null
        }
    }

    fun save() {
        saving = true; problem = null
        val items = ctx?.items ?: initial.context.mapNotNull(TrayItem::fromSpec)
        val fields = JsonObject(buildMap {
            put("name", JsonPrimitive(draft.name.trim()))
            put("kind", JsonPrimitive(draft.kind))
            put("model", draft.model?.let(::JsonPrimitive) ?: JsonNull)
            put("temp", JsonPrimitive(draft.temp))
            put("thinking", draft.thinking?.let(::JsonPrimitive) ?: JsonNull)
            put("tagline", JsonPrimitive(draft.tagline))
            put("prompt", JsonPrimitive(draft.prompt))
            put("context", JsonArray(items.map { it.payload() }))
            put("tool_families", draft.toolFamilies?.let { f -> JsonArray(f.map(::JsonPrimitive)) } ?: JsonNull)
            // Only when there is one: a nil must not wipe the night's choice.
            draft.icon?.let { put("icon", JsonPrimitive(it)) }
        })
        scope.launch {
            when (val outcome = store.save(initial.id.takeIf { it.isNotEmpty() }, fields)) {
                is SaveOutcome.Saved -> onClose()
                is SaveOutcome.NameTaken -> problem = strings("persona.error.name_taken", outcome.name)
                SaveOutcome.Failed -> problem = strings("persona.error.save_failed")
            }
            saving = false
        }
    }

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BackHandlerCompat(onBack)
        Column(Modifier.fillMaxSize().background(theme.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("common.cancel"), tint = theme.ink) }
                Column(Modifier.weight(1f)) {
                    Text(L(if (isEdit) "persona.header.edit" else "persona.header.new"), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = theme.inkMute)
                    Text(draft.name.ifEmpty { L("persona.header.untitled") }, style = wordmark(18.sp), color = if (draft.name.isEmpty()) theme.inkMute else theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isEdit) IconButton(onClick = { confirmDelete = true }, enabled = !saving) {
                    Icon(DomainGlyphs.of("trash"), L("persona.delete.help"), tint = ERASE, modifier = Modifier.size(20.dp))
                }
                Text(
                    L(if (isEdit) "common.save" else "common.create"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.padding(end = 8.dp).clip(CircleShape).background(if (canSave) accent else theme.inkMute.copy(alpha = 0.4f))
                        .clickable(enabled = canSave, onClick = ::save).padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider(color = theme.rule)
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    Preview(draft, selectedModel, weight = ctx?.total ?: draft.weight, count = ctx?.items?.size ?: draft.count, store = store)

                    // 1 · Identity
                    Section(1, L("persona.section.identity")) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg)
                                .border(0.5.dp, if (problem == null) theme.rule else Color.Red.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The mark is the way to its icon: a tap unfolds the choice.
                            Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = draft.kind == "domain") { showIcons = !showIcons }
                                .then(if (draft.kind == "domain") Modifier.accessibilityLabel(L("persona.field.icon")) else Modifier)) { DomainMark(draft, size = 60.dp) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field(draft.name, L("persona.field.name"), wordmark(20.sp).copy(color = theme.ink)) { draft = draft.copy(name = it); problem = null }
                                Field(draft.tagline, L("persona.field.tagline"), TextStyle(fontSize = 13.sp, color = theme.inkSoft)) { draft = draft.copy(tagline = it) }
                            }
                        }
                        problem?.let { Text(it, fontSize = 12.sp, color = Color.Red) }
                        if (showIcons && draft.kind == "domain") {
                            FieldLabel(L("persona.field.icon"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (symbol in DomainGlyphs.CHOICES) {
                                    val on = draft.symbol() == symbol
                                    Box(
                                        Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(if (on) accent.copy(alpha = 0.14f) else theme.bg)
                                            .border(if (on) 1.dp else 0.5.dp, if (on) accent else theme.rule, RoundedCornerShape(9.dp))
                                            .clickable { draft = draft.copy(icon = symbol) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(DomainGlyphs.of(symbol), symbol, tint = if (on) legible(accent, theme.isDark) else theme.inkSoft, modifier = Modifier.size(18.dp)) }
                                }
                            }
                        }
                        // Domain or reading companion: the member's hand on the sorting.
                        FieldLabel(L("persona.field.kind"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice(L("persona.kind.domain"), draft.kind == "domain") { draft = draft.copy(kind = "domain") }
                            Choice(L("persona.kind.companion"), draft.kind == "companion") { draft = draft.copy(kind = "companion") }
                        }
                        Text(L("persona.kind.hint"), fontSize = 11.sp, color = theme.inkMute)
                    }

                    // 2 · Model
                    Section(2, L("persona.section.model")) {
                        val available = state.models.filter { it.available }
                        val selectedId = draft.model ?: available.firstOrNull { it.isDefault }?.id ?: available.firstOrNull()?.id
                        val providers = PROVIDER_ORDER.filter { p -> available.any { it.provider == p } } +
                            available.map { it.provider }.filter { it !in PROVIDER_ORDER }.distinct().sorted()
                        for (p in providers) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(ProviderStyle.fill(p)))
                                Spacer(Modifier.width(8.dp))
                                Text(ProviderStyle.name(p), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.8.sp, color = theme.inkSoft)
                            }
                            for (m in available.filter { it.provider == p }) ModelCard(m, m.id == selectedId) { chooseModel(m) }
                        }
                    }

                    // 3 · Instructions
                    Section(3, L("persona.section.instructions")) {
                        Field(
                            draft.prompt, L("persona.instructions.placeholder"), TextStyle(fontSize = 13.5.sp, color = theme.ink), singleLine = false,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(10.dp)).background(theme.bg)
                                .border(0.5.dp, theme.rule, RoundedCornerShape(10.dp)).padding(12.dp),
                        ) { draft = draft.copy(prompt = it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FieldLabel(L("persona.field.creativity"), Modifier.weight(1f))
                            Text(creativityLabel(draft.temp), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = legible(accent, theme.isDark))
                        }
                        Slider(
                            value = draft.temp.toFloat(), onValueChange = { draft = draft.copy(temp = (kotlin.math.round(it * 20) / 20.0)) },
                            valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                        )
                        Row {
                            for ((i, k) in listOf("persona.creativity.precise", "persona.creativity.balanced", "persona.creativity.creative").withIndex()) {
                                if (i > 0) Spacer(Modifier.weight(1f))
                                Text(L(k), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = theme.inkMute)
                            }
                        }
                        // Only where the roster says the model takes the switch.
                        if (selectedModel?.thinkingIsOptional == true) {
                            FieldLabel(L("persona.field.reasoning"), Modifier.padding(top = 6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (v in listOf(null, false, true)) Choice(reasoningLabel(v), draft.thinking == v) { draft = draft.copy(thinking = v) }
                            }
                            Text(L("persona.reasoning.hint"), fontSize = 11.sp, color = theme.inkMute)
                        }
                    }

                    // 4 · Context
                    Section(4, L("persona.section.context")) {
                        Text(L("persona.context.description"), fontSize = 11.sp, color = theme.inkSoft)
                        if (context != null) ContextTray(context, onDone = null, onAddMore = { addingContext = true })
                    }

                    // 5 · Tools
                    Section(5, L("persona.section.tools")) {
                        if (selectedModel?.isLocal == true) Text(L("persona.tools.local_hint"), fontSize = 10.5.sp, color = theme.inkMute)
                        ToolFamilyPicker(state.families, draft.toolFamilies.orEmpty().toSet()) { draft = draft.copy(toolFamilies = it.toList()) }
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
        if (addingContext && context != null) AddContextSheet(context) { addingContext = false }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(L("persona.delete.title") + " « ${draft.name} » ?") },
            text = { Text(L("persona.delete.message")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false; saving = true
                    scope.launch {
                        // Refused: the editor stays open and says so (DomainEditor.delete).
                        if (runCatching { store.delete(initial.id) }.isFailure) {
                            saving = false
                            problem = strings("persona.error.delete_failed")
                            return@launch
                        }
                        // Its conversations go on with the everyday Maurice.
                        controller.refreshConversations()
                        saving = false
                        onClose()
                    }
                }) { Text(L("persona.delete.confirm"), color = ERASE) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }
}

@Composable
private fun Section(number: Int, title: String, content: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape), contentAlignment = Alignment.Center) {
                Text("$number", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkSoft)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
        }
        content()
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = LocalMauriceTheme.current.inkMute, modifier = modifier)

@Composable
private fun Field(value: String, placeholder: String, style: TextStyle, singleLine: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    BasicTextField(
        value, onChange, singleLine = singleLine, textStyle = style, cursorBrush = SolidColor(theme.ink), modifier = modifier,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = theme.inkMute))
                inner()
            }
        },
    )
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Text(
        label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        color = if (on) legible(accent, theme.isDark) else theme.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) accent.copy(alpha = 0.1f) else theme.bg)
            .border(if (on) 1.dp else 0.5.dp, if (on) accent else theme.ruleHard, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ModelCard(model: ModelInfo, selected: Boolean, onTap: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) accent.copy(alpha = 0.08f) else theme.bg)
            .border(if (selected) 1.dp else 0.5.dp, if (selected) accent else theme.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).background(if (selected) accent else Color.Transparent)
                .border(1.5.dp, if (selected) accent else theme.ruleHard, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White)) }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                Spacer(Modifier.width(8.dp))
                Text(
                    L(if (model.isLocal) "model.ondevice" else "model.cloud"), fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, color = theme.inkSoft,
                    modifier = Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (model.desc.isNotEmpty()) Text(model.desc, fontSize = 11.5.sp, color = theme.inkSoft)
            if (model.sub.isNotEmpty()) Text(model.sub, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute)
        }
    }
}

/** What the domain will look like, as it is being made (`DomainPreview`). */
@Composable
private fun Preview(draft: Domain, model: ModelInfo?, weight: Int, count: Int, store: DomainStore) {
    val theme = LocalMauriceTheme.current
    val state by store.state.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.bg).border(0.5.dp, theme.rule, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DomainMark(draft, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(L(if (draft.isCompanion) "domains.kicker.companion" else "domains.kicker.domain"), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = theme.inkMute)
                Text(draft.name.ifEmpty { L("persona.preview.untitled") }, style = wordmark(19.sp), color = if (draft.name.isEmpty()) theme.inkMute else theme.ink, maxLines = 2)
            }
        }
        Text(
            draft.tagline.ifEmpty { draft.prompt.take(120).ifEmpty { L("persona.preview.no_tagline") } },
            fontSize = 12.sp, color = theme.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(color = theme.rule)
        PreviewRow(L("persona.preview.model"), state.resolvedModel(draft)?.name ?: "—")
        PreviewRow(
            L("persona.preview.context"),
            if (count == 0) L("persona.preview.none") else L(if (count == 1) "persona.preview.sources.one" else "persona.preview.sources.other", count, fmtTok(weight)),
        )
        PreviewRow(L("persona.preview.creativity"), creativityLabel(draft.temp))
        if (model?.thinkingIsOptional == true) PreviewRow(L("persona.preview.reasoning"), reasoningLabel(draft.thinking))
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    val theme = LocalMauriceTheme.current
    Row {
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.6.sp, color = theme.inkMute, modifier = Modifier.width(90.dp))
        Text(value, fontSize = 12.sp, color = theme.ink)
    }
}
