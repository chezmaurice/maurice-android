package eu.chezmaurice.carnet.ui

import eu.chezmaurice.ui.L
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import eu.chezmaurice.ui.MauriceIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.chezmaurice.carnet.LocalChat
import eu.chezmaurice.maurice.platform.rememberMicPermission
import eu.chezmaurice.maurice.platform.rememberRecorder
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.launch

/**
 * Speak instead of typing (`DictationMic`): a tap records, another stops,
 * and the household's Whisper hands back the words. Absent when the
 * household has no dictation.
 */
@Composable
fun DictationMic(dictateLabel: String? = null, onWords: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val chat = LocalChat.current ?: return
    val available by chat.canDictate.collectAsState()
    if (!available) return
    val scope = rememberCoroutineScope()
    val recorder = rememberRecorder()
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    val askMic = rememberMicPermission { granted -> if (granted && recorder.start()) recording = true }
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (recording) theme.ink else theme.surface).border(0.5.dp, theme.ruleHard, CircleShape)
            .clickable(enabled = !transcribing, role = Role.Button) {
                if (!recording) askMic()
                else {
                    recording = false
                    val clip = recorder.stop() ?: return@clickable
                    transcribing = true
                    scope.launch {
                        chat.transcribe(clip)?.takeIf { it.isNotBlank() }?.let(onWords)
                        transcribing = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val label = if (recording) L("share.stop_dictation") else dictateLabel ?: L("carnet.dictate")
        if (transcribing) CircularProgressIndicator(Modifier.size(14.dp).semantics { contentDescription = label }, strokeWidth = 1.5.dp, color = theme.inkMute)
        else Icon(MauriceIcons.Mic, label, tint = if (recording) theme.bg else theme.inkSoft, modifier = Modifier.size(17.dp))
    }
}
