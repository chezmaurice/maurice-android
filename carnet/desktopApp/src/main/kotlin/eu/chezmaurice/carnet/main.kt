package eu.chezmaurice.carnet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.chezmaurice.core.session.FilePairings
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.FileKeyValueStore
import kotlin.system.exitProcess

fun main() {
    // The households shared with the Maurice app (SharedPairings): one file both apps read.
    val session = Session(FileKeyValueStore("Carnet"), prefix = "carnet", shared = FilePairings())
    // CI: open the real window, draw for a few seconds, leave with 0.
    if (System.getenv("MAURICE_SMOKE") != null) {
        Thread { Thread.sleep(8_000); println("smoke: window up"); exitProcess(0) }.apply { isDaemon = true }.start()
    }
    application {
        Window(onCloseRequest = ::exitApplication, icon = appIcon(), title = "Carnet", state = rememberWindowState(size = DpSize(520.dp, 860.dp))) {
            // Back in front: what the Maurice app changed meanwhile.
            androidx.compose.runtime.LaunchedEffect(window) {
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = session.applyShared()
                })
            }
            CarnetApp(session)
        }
    }
}

/** The window's icon (and the taskbar's): `icon.png`, from the export in `art/icons-2026-11/`. */
@Composable
private fun appIcon(): BitmapPainter? = remember {
    object {}.javaClass.getResourceAsStream("/icon.png")?.use { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }
}
