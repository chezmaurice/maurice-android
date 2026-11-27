package eu.chezmaurice.core.settings

import eu.chezmaurice.core.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The mark that shows Maurice at work — `ThinkingPulse.Style`: four
 * candidates, picked in Settings so they can be tried in a live conversation.
 */
enum class ThinkingPulseStyle(val raw: String) {
    /** A dot breathing, with a ring that ripples out of it and fades. */
    Halo("halo"),
    /** Three dots circling, the tail ones fainter — a comet. */
    Orbit("orbit"),
    /** A short stroke that draws itself and lifts, like a pen thinking. */
    Stroke("stroke"),
    /** Maurice's hat, nodding. */
    Hat("hat");

    val labelKey: String get() = "settings.pulse.$raw"

    companion object {
        val default = Halo
        /** An unknown or missing value is the default, as `Style(rawValue:) ?? .defaultChoice`. */
        fun of(raw: String?): ThinkingPulseStyle = entries.firstOrNull { it.raw == raw } ?: default
    }
}

/**
 * The choice, a device preference (`@AppStorage("maurice.thinkingPulse")`):
 * the same for every household and member of the device, never sent.
 */
class ThinkingPulsePreference(private val store: KeyValueStore) {
    private val _style = MutableStateFlow(ThinkingPulseStyle.of(store.get(KEY)))
    val style: StateFlow<ThinkingPulseStyle> = _style.asStateFlow()

    fun choose(style: ThinkingPulseStyle) {
        store.put(KEY, style.raw)
        _style.value = style
    }

    companion object {
        const val KEY = "maurice.thinkingPulse"
    }
}
