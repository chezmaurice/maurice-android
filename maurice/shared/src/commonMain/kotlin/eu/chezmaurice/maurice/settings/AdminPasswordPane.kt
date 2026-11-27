package eu.chezmaurice.maurice.settings

import eu.chezmaurice.core.net.shownMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import kotlinx.coroutines.launch

private const val MINIMUM = 12

/**
 * The administrator changes their own password (`AdminPasswordPane`,
 * `POST /api/auth/password`): the current one, then the new one twice. The
 * server holds the rules; the length and the username are checked here first,
 * so that what is said is in the member's language.
 */
@Composable
internal fun AdminPasswordPane(session: Session, member: Member) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun save() {
        error = null; done = false
        val name = member.username.orEmpty()
        error = when {
            fresh != again -> strings("password.mismatch")
            fresh.length < MINIMUM -> strings("password.short")
            name.length >= 3 && fresh.lowercase().contains(name.lowercase()) -> strings("password.name")
            else -> null
        }
        if (error != null) return
        val api = session.api()?.let(::SettingsApi) ?: return
        busy = true
        scope.launch {
            try {
                api.changePassword(current, fresh)
                current = ""; fresh = ""; again = ""
                done = true
            } catch (e: ApiError) {
                error = when (e.status) {
                    // 403 since 12 Nov 2026 (a 401 read as an ended session); 401 from older servers.
                    403, 401 -> strings("password.wrong")
                    400 -> strings("password.weak")
                    429 -> strings("password.too_many")
                    else -> e.shown
                }
            } catch (e: Throwable) {
                error = e.shownMessage()
            } finally { busy = false }
        }
    }

    SetCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((value, key, set) in listOf<Triple<String, String, (String) -> Unit>>(
                Triple(current, "password.current") { current = it },
                Triple(fresh, "password.new") { fresh = it },
                Triple(again, "password.again") { again = it },
            )) {
                OutlinedTextField(
                    value = value, onValueChange = set, singleLine = true, label = { Text(L(key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    SetCaption(L("password.rule"))
    error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFC0392B)) }
    if (done) Text("✓  " + L("password.done"), fontSize = 13.5.sp, color = theme.ink)
    Button(
        onClick = ::save,
        enabled = !busy && current.isNotEmpty() && fresh.isNotEmpty() && again.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.White),
    ) { Text(L("password.save")) }
}
