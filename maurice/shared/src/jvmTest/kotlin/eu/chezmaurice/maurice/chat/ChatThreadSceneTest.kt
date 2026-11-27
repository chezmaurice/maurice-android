package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.MauriceThemeProvider
import eu.chezmaurice.ui.Strings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ChatThread] laid out and scrolled by Compose's desktop engine, at a density
 * of 1 (a dp is a pixel) in an 800 px tall viewport: where the rows really
 * land, which the pure [ThreadScroll] cannot tell.
 */
class ChatThreadSceneTest {
    private val me = "u1"
    private val viewport = 800

    private class Thread0 {
        val messages = mutableStateListOf<Message>()
        val heights = mutableMapOf<String, Int>()
        var live by mutableStateOf(false)
        var liveHeight by mutableIntStateOf(0)
        val tops = mutableMapOf<String, Float>()
        var liveBottom = 0f
    }

    private fun message(id: String, mine: Boolean, height: Int, t: Thread0) =
        Message(id = id, role = if (mine) "user" else "assistant", content = id, authorId = if (mine) "u1" else null)
            .also { t.heights[id] = height }

    /**
     * Renders for that many milliseconds; drags a finger down the screen by
     * that many pixels (negative: up, toward the end); turns the wheel
     * (positive: toward the end); writes `build/screenshots/<name>.png`.
     */
    private class Driver(val render: (Int) -> Unit, val drag: (Float) -> Unit, val wheel: (Float) -> Unit, val shot: (String) -> Unit)

    private fun scene(t: Thread0, scroll: ThreadScroll, block: (render: (Int) -> Unit) -> Unit) =
        drive(t, scroll) { block(it.render) }

    private fun drive(t: Thread0, scroll: ThreadScroll, block: (Driver) -> Unit) {
        var time = 0L
        ImageComposeScene(width = 400, height = viewport, density = Density(1f)) {
            CompositionLocalProvider(LocalStrings provides Strings(emptyMap())) {
                MauriceThemeProvider("cream") {
                    ChatThread(
                        scroll = scroll, conversation = "c", messages = t.messages, memberId = me,
                        turnLive = t.live, rowsAfter = if (t.live) 1 else 0,
                    ) {
                        items(t.messages, key = { it.id }) { m ->
                            Box(Modifier.fillMaxWidth().height(t.heights.getValue(m.id).dp).onGloballyPositioned { t.tops[m.id] = it.positionInRoot().y }.padding(4.dp).background(if (m.role == "user") Color(0xFFB9C7E6) else Color(0xFFE2DCCB)))
                        }
                        if (t.live) item(key = "live") {
                            Box(Modifier.fillMaxWidth().height(t.liveHeight.dp).onGloballyPositioned { t.liveBottom = it.positionInRoot().y + it.size.height }.padding(4.dp).background(Color(0xFFE8C9A8)))
                        }
                    }
                }
            }
        }.use { s ->
            val render = { ms: Int ->
                repeat(ms / 16) {
                    Thread.sleep(16)
                    time += 16_000_000L
                    s.render(time)
                }
            }
            // A slow finger: no fling after it.
            val drag = { distance: Float ->
                var y = 400f - distance / 2
                s.sendPointerEvent(PointerEventType.Press, Offset(200f, y), type = PointerType.Touch)
                render(32)
                repeat(20) {
                    y += distance / 20
                    s.sendPointerEvent(PointerEventType.Move, Offset(200f, y), type = PointerType.Touch)
                    render(32)
                }
                render(200)
                s.sendPointerEvent(PointerEventType.Release, Offset(200f, y), type = PointerType.Touch)
                render(400)
            }
            // Positive: toward the end.
            val wheel = { delta: Float ->
                s.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 400f), scrollDelta = Offset(0f, delta))
                render(400)
            }
            val shot = { name: String ->
                val out = File("build/screenshots/thread-$name.png").also { it.parentFile.mkdirs() }
                out.writeBytes(s.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            block(Driver(render, drag, wheel, shot))
        }
    }

    @Test fun `a hand to the end of a long reply gives the follow back, and up takes it away`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        drive(t, scroll) { d ->
            d.render(200)
            scroll.sent()
            t.messages += message("q", mine = true, height = 60, t)
            t.live = true
            t.liveHeight = 1500
            d.render(1000)
            near(8f, t.tops["q"], "the question pinned")
            var turns = 0
            while (scroll.holding && turns++ < 60) d.drag(-300f)
            assertFalse(scroll.holding, "released at the end of the text")
            assertTrue(t.liveBottom <= viewport, "the end in view")
            // Followed from here: the reply grows, its end stays on the padding.
            t.liveHeight = 1800
            d.render(300)
            near(viewport - 16f, t.liveBottom, "the end followed")
            // Up, the end out of view: no longer followed.
            d.drag(400f)
            assertTrue(scroll.showsScrollToEnd)
            assertFalse(scroll.following)
            val bottom = t.liveBottom
            t.liveHeight = 2100
            d.render(300)
            near(bottom + 300f, t.liveBottom, "the reply grows out of view")
        }
    }

    @Test fun `a hand on a reply that fits under its question gives nothing back`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        drive(t, scroll) { d ->
            d.render(200)
            scroll.sent()
            t.messages += message("q", mine = true, height = 60, t)
            t.live = true
            t.liveHeight = 200
            d.render(1000)
            repeat(5) { d.drag(-300f) }
            assertTrue(scroll.holding)
            near(8f, t.tops["q"], "the question still on top")
            t.liveHeight = 1500
            d.render(300)
            near(8f, t.tops["q"], "the question once the reply passes the fold")
        }
    }

    @Test fun `the wheel works as the hand does - on Windows and Linux`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        drive(t, scroll) { d ->
            d.render(200)
            scroll.sent()
            t.messages += message("q", mine = true, height = 60, t)
            t.live = true
            t.liveHeight = 1500
            d.render(1000)
            var turns = 0
            while (scroll.holding && turns++ < 80) d.wheel(3f)
            assertFalse(scroll.holding, "released at the end of the text")
            t.liveHeight = 1800
            d.render(300)
            near(viewport - 16f, t.liveBottom, "the end followed")
            repeat(4) { d.wheel(-3f) }
            assertFalse(scroll.following, "up stops the follow")
            val bottom = t.liveBottom
            t.liveHeight = 2100
            d.render(300)
            near(bottom + 300f, t.liveBottom, "the reply grows out of view")
        }
    }

    private fun near(expected: Float, actual: Float?, what: String) =
        assertTrue(actual != null && abs(expected - actual) <= 2f, "$what: expected ≈$expected, was $actual")

    /** Ten earlier messages, the thread open on its end. */
    private fun history(t: Thread0) = repeat(10) { i -> t.messages += message("m$i", mine = i % 2 == 0, height = 100, t) }

    private fun opened() = ThreadScroll(air = 8f, bottomPadding = 16f).also { it.opened("c", created = false) }

    @Test fun `the question sent goes to the top, and stays there while the reply grows and lands`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        drive(t, scroll) { d ->
            val render = d.render
            render(200)
            d.shot("1-opened")
            // Opened on its end: the last message's bottom on the padding.
            near(viewport - 16f - 100f, t.tops["m9"], "the end on opening")

            scroll.sent()
            t.messages += message("q", mine = true, height = 60, t)
            t.live = true
            render(1000)
            near(8f, t.tops["q"], "the question pinned")
            t.liveHeight = 150
            render(150)
            d.shot("2-pinned-short-reply")

            for (h in listOf(200, 500, 900, 2000)) {
                t.liveHeight = h
                render(150)
                near(8f, t.tops["q"], "the question while the reply is $h tall")
            }
            assertTrue(scroll.showsScrollToEnd, "the ↓ shows: text under the fold")
            d.shot("3-reply-under-the-fold")

            // The reply typed out: its message takes the row's place. No jump.
            t.live = false
            t.messages += message("a", mine = false, height = 2000, t)
            render(500)
            near(8f, t.tops["q"], "the question once the reply is in place")
            assertTrue(scroll.holding)
        }
    }

    @Test fun `a short reply leaves the question on top with the room under it`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        scene(t, scroll) { render ->
            render(200)
            scroll.sent()
            t.messages += message("q", mine = true, height = 60, t)
            t.live = true
            t.liveHeight = 120
            render(1000)
            near(8f, t.tops["q"], "the question pinned")
            near(8f + 60f + 120f, t.liveBottom, "the reply under it")
            assertFalse(scroll.showsScrollToEnd, "no ↓: the end is in view")
        }
    }

    @Test fun `of a long question only its last third of a screen stays on top`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        scene(t, scroll) { render ->
            render(200)
            scroll.sent()
            t.messages += message("q", mine = true, height = 600, t)
            t.live = true
            render(1000)
            // Its bottom a third of the viewport down, plus the air.
            near(viewport / 3f + 8f - 600f, t.tops["q"], "the long question's top")
        }
    }

    @Test fun `a reader following stays on the end as a message arrives`() {
        val t = Thread0(); history(t)
        val scroll = opened()
        scene(t, scroll) { render ->
            render(200)
            t.messages += message("other", mine = false, height = 300, t)
            render(600)
            near(viewport - 16f - 300f, t.tops["other"], "the new message at the end")
        }
    }
}
