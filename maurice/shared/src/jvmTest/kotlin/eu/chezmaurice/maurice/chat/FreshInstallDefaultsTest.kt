package eu.chezmaurice.maurice.chat

import eu.chezmaurice.core.store.MemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A new installation shows each turn's cost; one already in use keeps its setting (22 November 2026). */
class FreshInstallDefaultsTest {
    @Test fun aNewInstallationShowsTheCost() {
        val store = MemoryKeyValueStore()
        FreshInstallDefaults.applyOnce(store)
        assertEquals("true", store.get(TurnCostPref.KEY))
    }

    @Test fun anInstallationInUseKeepsItsSetting() {
        val withHouseholds = MemoryKeyValueStore().apply { put("maurice.households", "[]") }
        FreshInstallDefaults.applyOnce(withHouseholds)
        assertNull(withHouseholds.get(TurnCostPref.KEY))
        val withDevice = MemoryKeyValueStore().apply { put("device.id", "abc") }
        FreshInstallDefaults.applyOnce(withDevice)
        assertNull(withDevice.get(TurnCostPref.KEY))
    }

    @Test fun onlyOnceSoTurningItOffSticks() {
        val store = MemoryKeyValueStore()
        FreshInstallDefaults.applyOnce(store)
        store.put(TurnCostPref.KEY, null) // turned off in Settings
        FreshInstallDefaults.applyOnce(store)
        assertNull(store.get(TurnCostPref.KEY))
    }
}
