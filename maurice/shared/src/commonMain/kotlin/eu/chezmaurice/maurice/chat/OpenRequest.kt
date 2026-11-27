package eu.chezmaurice.maurice.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A conversation to open, from a notification's tap: in which household, which one. */
data class OpenTarget(val householdTag: String?, val conversationId: String)

object OpenRequest {
    private val _pending = MutableStateFlow<OpenTarget?>(null)
    val pending: StateFlow<OpenTarget?> = _pending.asStateFlow()
    fun put(target: OpenTarget) { _pending.value = target }
    fun take(): OpenTarget? = _pending.value.also { _pending.value = null }
}
