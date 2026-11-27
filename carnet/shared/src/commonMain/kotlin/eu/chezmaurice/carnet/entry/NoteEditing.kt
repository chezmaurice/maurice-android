package eu.chezmaurice.carnet.entry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.chezmaurice.carnet.platform.shortDate
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.MineItem
import eu.chezmaurice.core.carnet.NoteEditError
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Rewriting or taking off a note of the member's side (`NoteEditing.swift`):
// a tap on it opens the note sheet filled with it, a long press offers to
// take it off. Nothing is offered by a server that does not say a note may be.

/** A note opened to be rewritten: the entry it is on (an entry shared with the member is written in its owner's garden) and the note as it was read. */
data class NoteEditTarget(val entry: ShelfEntry, val item: MineItem)

object NoteEdits {
    /** Rewrite the note, then read again what shows it — also when it changed in the meantime, so what shows is current. */
    suspend fun save(garden: GardenApi, target: NoteEditTarget, text: String, quote: String?, page: String?, reload: suspend () -> Unit) {
        val id = target.item.serverId ?: throw NoteEditError(NoteEditError.Kind.Changed)
        try {
            garden.editNote(target.entry, id, text, quote, page)
            reload()
        } catch (e: NoteEditError) {
            if (e.kind == NoteEditError.Kind.Changed) reload()
            throw e
        }
    }

    suspend fun delete(garden: GardenApi, target: NoteEditTarget, reload: suspend () -> Unit) {
        val id = target.item.serverId ?: throw NoteEditError(NoteEditError.Kind.Changed)
        try {
            garden.deleteNote(target.entry, id)
            reload()
        } catch (e: NoteEditError) {
            if (e.kind == NoteEditError.Kind.Changed) reload()
            throw e
        }
    }

    /** What went wrong, in the member's language: the note changed, is not theirs, the network is out; else the server's word. */
    fun message(e: Throwable, strings: Strings): String = when {
        e is NoteEditError && e.kind == NoteEditError.Kind.Changed -> strings("note.edit.changed")
        e is NoteEditError -> strings("note.edit.forbidden")
        e is kotlinx.io.IOException -> strings("note.edit.offline")
        else -> e.message.orEmpty()
    }

    /** "edited 3 Nov 2026", in the member's language; the day as written when it cannot be read. */
    fun editedLine(day: String, strings: Strings): String {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(day)
        val shown = m?.let { runCatching { shortDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), withYear = true) }.getOrNull() }
        return strings("note.edited", shown ?: day)
    }
}

/**
 * A note of the member's side, as the fiche and the Notes list show it: a tap
 * opens it to be rewritten when the member may; a long press offers what they
 * may do with it (an owner, on what someone else signed on their shared entry,
 * may only take it off).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteActions(item: MineItem, onEdit: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    if (!item.canEdit && !item.canDelete) { content(); return }
    var menu by remember { mutableStateOf(false) }
    val theme = LocalMauriceTheme.current
    Box(Modifier.combinedClickable(onClick = { if (item.canEdit) onEdit() else menu = true }, onLongClick = { menu = true })) {
        content()
        DropdownMenu(menu, { menu = false }, containerColor = theme.surface) {
            if (item.canEdit) DropdownMenuItem(text = { Text(L("note.edit.action")) }, onClick = { menu = false; onEdit() })
            if (item.canDelete) DropdownMenuItem(text = { Text(L("note.edit.delete"), color = Color(0xFFC0392B)) }, onClick = { menu = false; onDelete() })
        }
    }
}

/**
 * The sheet a note is rewritten in, and the question asked before one is taken
 * off from its long-press menu. [reload] reads again what shows the note.
 */
@Composable
fun NoteEditing(garden: GardenApi, editing: NoteEditTarget?, deleting: NoteEditTarget?, onEditingDone: () -> Unit, onDeletingDone: () -> Unit, reload: suspend () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    editing?.let { target ->
        NoteSheet(
            title = target.entry.title,
            offersPage = target.entry.source == null || target.item.place?.page != null,
            editing = target.item,
            onDelete = if (target.item.canDelete) ({ NoteEdits.delete(garden, target, reload) }) else null,
            onDismiss = onEditingDone,
        ) { text, quote, page -> NoteEdits.save(garden, target, text, quote, page, reload) }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = onDeletingDone,
            title = { Text(L("note.edit.delete_confirm")) },
            text = { Text(L("note.edit.delete_hint")) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletingDone()
                    scope.launch {
                        try { NoteEdits.delete(garden, target, reload) } catch (e: CancellationException) { throw e } catch (e: Throwable) { failure = NoteEdits.message(e, strings) }
                    }
                }) { Text(L("note.edit.delete"), color = Color(0xFFC0392B)) }
            },
            dismissButton = { TextButton(onClick = onDeletingDone) { Text(L("common.cancel"), color = theme.inkSoft) } },
            containerColor = theme.surface,
        )
    }
    failure?.let { text ->
        AlertDialog(
            onDismissRequest = { failure = null }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(L("common.ok"), color = theme.ink) } },
            containerColor = theme.surface,
        )
    }
}
