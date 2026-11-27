package eu.chezmaurice.core

import eu.chezmaurice.core.model.formatServerDate
import eu.chezmaurice.core.model.parseServerDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerDatesTest {
    private val paris = TimeZone.of("Europe/Paris")

    @Test fun naiveIsUtc() = assertEquals("21/10/2026 22:00", formatServerDate("2026-10-21 20:00:00", paris))
    @Test fun isoZ() = assertEquals("21/10/2026 22:00", formatServerDate("2026-10-21T20:00:00.123Z", paris))
    @Test fun offsetMicros() { assertNotNull(parseServerDate("2026-09-23T22:23:06.506038+02:00")) }
    @Test fun dateOnly() = assertEquals("21/10/2026 02:00", formatServerDate("2026-10-21", paris))
    @Test fun nonsense() = assertNull(parseServerDate("hier"))
}
