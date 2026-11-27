package eu.chezmaurice.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * iOS's `.accessibilityLabel` on a control whose content says nothing a
 * screen reader should read (a glyph, a member's initial, a mark): [label]
 * replaces it. A button unless [button] is false. Put it after the
 * `clickable` in the chain — a clickable after it would lose its action.
 */
fun Modifier.accessibilityLabel(label: String, button: Boolean = true): Modifier =
    clearAndSetSemantics { contentDescription = label; if (button) role = Role.Button }

/**
 * iOS's `.accessibilityLabel` on a control with words of its own on screen:
 * [label] is what a screen reader says for it, and the words stay in the
 * tree for what reads the screen (the UI scenarios).
 */
fun Modifier.accessibilityName(label: String, button: Boolean = true): Modifier =
    semantics { contentDescription = label; if (button) role = Role.Button }

/** iOS's `.accessibilityHidden(true)`: nothing here for a screen reader. */
fun Modifier.accessibilityHidden(hidden: Boolean = true): Modifier =
    if (hidden) clearAndSetSemantics {} else this
