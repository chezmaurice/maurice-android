package eu.chezmaurice.maurice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.MauriceThemeProvider
import eu.chezmaurice.ui.Strings
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** A window or a phone, in dp. */
data class Frame(val name: String, val width: Int, val height: Int)

object Frames {
    val smallPhone = Frame("phone-small", 360, 640)
    val phone = Frame("phone", 393, 852)
    val tablet = Frame("tablet", 1024, 768)
    val desktop = Frame("desktop", 1280, 800)
    /** A phone as tall as a long reply, to see it whole. */
    val tallPhone = Frame("phone-tall", 393, 1700)
    val all = listOf(smallPhone, phone, tablet, desktop)
}

/** How the member set their device up. */
data class Look(val name: String, val palette: String, val fontScale: Float = 1f, val language: String = "fr")

object Looks {
    val day = Look("day", "cream")
    val night = Look("night", "studio")
    val bigText = Look("bigtext", "cream", fontScale = 1.6f)
    val english = Look("en", "cream", language = "en")
}

private val stringsDir = File("src/commonMain/composeResources/files/strings")
private val androidDir = File("src/commonMain/composeResources/files/android")
private fun table(language: String) = Strings.parse(stringsDir.resolve("$language.strings").readText()) +
    (androidDir.resolve("$language.strings").takeIf { it.exists() }?.let { Strings.parse(it.readText()) } ?: emptyMap())
private val english by lazy { table("en") }
private fun strings(language: String) = Strings(if (language == "en") english else table(language), english)

/**
 * Render [content] with Compose's desktop engine — what runs on Windows and
 * Linux — and write `build/screenshots/<name>.png`. Several frames are drawn
 * so that fonts, vector images and Markdown, which arrive asynchronously, are
 * in place.
 */
fun snapshot(name: String, frame: Frame, look: Look, content: @Composable () -> Unit): File {
    val density = 2f
    val out = File("build/screenshots/$name--${frame.name}--${look.name}.png")
    out.parentFile.mkdirs()
    ImageComposeScene(
        width = (frame.width * density).toInt(),
        height = (frame.height * density).toInt(),
        density = Density(density, look.fontScale),
    ) {
        CompositionLocalProvider(LocalStrings provides strings(look.language)) {
            MauriceThemeProvider(look.palette) { content() }
        }
    }.use { scene ->
        var image = scene.render(0)
        for (i in 1..8) {
            Thread.sleep(40)
            image = scene.render(i * 100_000_000L)
        }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    return out
}
