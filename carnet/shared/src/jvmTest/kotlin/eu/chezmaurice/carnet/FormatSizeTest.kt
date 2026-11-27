package eu.chezmaurice.carnet

import eu.chezmaurice.carnet.reading.formatSize
import kotlin.test.Test
import kotlin.test.assertEquals

/** What `ByteCountFormatter` (`.file`) printed on macOS, in each language (the separator is the region's). */
class FormatSizeTest {
    private val sizes = listOf(0L, 1, 2, 999, 1000, 1500, 2500, 999_499, 999_500, 1_050_000, 2_250_000,
        12_345_678, 999_949_999, 999_950_000, 1_234_567_890, 1_500_000_000_000, -1500)

    @Test fun english() = assertEquals(
        listOf("Zero KB", "1 byte", "2 bytes", "999 bytes", "1 KB", "2 KB", "3 KB", "999 KB", "1 MB", "1.1 MB", "2.3 MB",
            "12.3 MB", "999.9 MB", "1 GB", "1.23 GB", "1.5 TB", "-2 KB"),
        sizes.map { formatSize(it, "en", '.') },
    )

    @Test fun french() = assertEquals(
        listOf("Zéro ko", "1 octet", "2 octets", "999 octets", "1 ko", "2 ko", "3 ko", "999 ko", "1 Mo", "1,1 Mo", "2,3 Mo",
            "12,3 Mo", "999,9 Mo", "1 Go", "1,23 Go", "1,5 To", "-2 ko"),
        sizes.map { formatSize(it, "fr", ',') },
    )

    @Test fun german() = assertEquals(listOf("0 KB", "1 Byte", "2 Byte", "1,5 GB"), listOf(0L, 1, 2, 1_500_000_000).map { formatSize(it, "de", ',') })

    @Test fun unknownLanguageIsEnglish() = assertEquals("12.3 MB", formatSize(12_345_678, "ja", '.'))
}
