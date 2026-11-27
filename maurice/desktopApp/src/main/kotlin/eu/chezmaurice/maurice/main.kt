package eu.chezmaurice.maurice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.chezmaurice.core.session.DeviceInfo
import eu.chezmaurice.core.session.FilePairings
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.FileKeyValueStore
import kotlin.system.exitProcess

/**
 * What the household's server is told this app runs on: Windows or Linux.
 * None on the Mac, where this build is only a development convenience.
 */
private fun desktopDevice(): DeviceInfo? {
    val os = System.getProperty("os.name").orEmpty()
    val platform = when {
        os.startsWith("Windows") -> "Windows"
        os.startsWith("Linux") -> "Linux"
        else -> return null
    }
    return DeviceInfo(platform = platform, model = null, osVersion = System.getProperty("os.version"), app = "maurice", appVersion = null)
}

fun main() {
    // The households shared with Carnet (SharedPairings): one file both apps read.
    val store = FileKeyValueStore("Maurice")
    eu.chezmaurice.maurice.chat.FreshInstallDefaults.applyOnce(store)
    val session = Session(store, prefix = "maurice", device = desktopDevice(), shared = FilePairings())
    // CI: open the real window, draw for a few seconds, leave with 0. Proves the
    // app starts on that system (AWT, Skia, fonts), which a test scene cannot.
    if (System.getenv("MAURICE_SMOKE") != null) {
        Thread { Thread.sleep(8_000); println("smoke: window up"); exitProcess(0) }.apply { isDaemon = true }.start()
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            icon = appIcon(),
            title = "Maurice",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            // Back in front: what Carnet changed meanwhile.
            androidx.compose.runtime.LaunchedEffect(window) {
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = session.applyShared()
                })
            }
            MauriceApp(session)
        }
    }
}

/** The window's icon (and the taskbar's): `icon.png`, from the export in `art/icons-2026-11/`. */
@Composable
private fun appIcon(): BitmapPainter? = remember {
    object {}.javaClass.getResourceAsStream("/icon.png")?.use { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }
}
