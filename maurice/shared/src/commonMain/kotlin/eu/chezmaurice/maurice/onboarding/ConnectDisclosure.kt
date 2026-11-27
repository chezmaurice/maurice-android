package eu.chezmaurice.maurice.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme

/**
 * Shown once per server to a member who does not run it — `ConnectDisclosureOverlay`:
 * who controls Maurice here, what others can see, that whoever runs it can
 * step into an account (and the member is told), how to leave. The iOS words,
 * in the app's seven languages. Cancel forgets the household.
 */
@Composable
fun ConnectDisclosure(onAccept: () -> Unit, onCancel: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            BoaterHat(size = 32.dp)
            Text(L("connect.disclosure.title"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = theme.ink)
            Text(L("connect.disclosure.intro"), fontSize = 16.sp, color = theme.ink)
            for (key in listOf("connect.disclosure.operator", "connect.disclosure.rooms", "connect.disclosure.private", "connect.disclosure.safety")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", fontSize = 15.sp, color = theme.inkSoft)
                    Text(L(key), fontSize = 15.sp, color = theme.inkSoft, lineHeight = 21.sp)
                }
            }
            Text(L("connect.disclosure.warning"), fontSize = 14.sp, color = theme.inkMute, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = theme.ink, contentColor = theme.bg), modifier = Modifier.fillMaxWidth()) {
                Text(L("connect.disclosure.accept"))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(L("connect.disclosure.cancel"), color = theme.ink) }
        }
    }
}
