package eu.chezmaurice.core

import eu.chezmaurice.core.settings.ThinkingPulsePreference
import eu.chezmaurice.core.settings.ThinkingPulseStyle
import eu.chezmaurice.core.store.MemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkingPulseTest {
    @Test fun theFourStylesInIosOrderWithTheirKeys() {
        assertEquals(listOf("halo", "orbit", "stroke", "hat"), ThinkingPulseStyle.entries.map { it.raw })
        assertEquals("settings.pulse.stroke", ThinkingPulseStyle.Stroke.labelKey)
    }

    @Test fun nothingStoredOrUnknownIsTheHalo() {
        assertEquals(ThinkingPulseStyle.Halo, ThinkingPulsePreference(MemoryKeyValueStore()).style.value)
        val store = MemoryKeyValueStore().apply { put("maurice.thinkingPulse", "wave") }
        assertEquals(ThinkingPulseStyle.Halo, ThinkingPulsePreference(store).style.value)
    }

    @Test fun theChoiceIsKeptUnderTheIosKeyAndReadAgain() {
        val store = MemoryKeyValueStore()
        val pref = ThinkingPulsePreference(store)
        pref.choose(ThinkingPulseStyle.Hat)
        assertEquals(ThinkingPulseStyle.Hat, pref.style.value)
        assertEquals("hat", store.get("maurice.thinkingPulse"))
        assertEquals(ThinkingPulseStyle.Hat, ThinkingPulsePreference(store).style.value)
    }
}
