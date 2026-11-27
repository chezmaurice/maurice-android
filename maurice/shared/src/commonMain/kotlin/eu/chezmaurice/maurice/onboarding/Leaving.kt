package eu.chezmaurice.maurice.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.launch

/**
 * "Forget <household> on this device?" — every member signed in here is
 * signed out and what the device keeps for them is erased. [pending]: what
 * is still waiting to be sent (Carnet's captures), lost with it.
 */
@Composable
fun ForgetHouseholdDialog(session: Session, household: Household, pending: Int = 0, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        title = { Text(L("household.forget.title", household.name), color = theme.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("household.forget.body"), color = theme.inkSoft)
                when {
                    pending == 1 -> Text(L("household.forget.pending.one"), color = Color(0xFFC0392B))
                    pending > 1 -> Text(L("household.forget.pending.other", pending), color = Color(0xFFC0392B))
                }
            }
        },
        confirmButton = {
            // Dismissed once done: leaving composition first would cancel the sign-outs on the server.
            TextButton(onClick = { scope.launch { session.disconnect(household.id); onDismiss() } }) {
                Text(L("household.forget.confirm"), color = Color(0xFFC0392B))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("picker.pin.cancel"), color = theme.inkSoft) } },
    )
}
