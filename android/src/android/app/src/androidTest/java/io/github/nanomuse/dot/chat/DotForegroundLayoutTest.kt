package io.github.nanomuse.dot.chat

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.overlay.foregroundFrame
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity
import io.github.nanomuse.dot.ui.DotTokens
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real Android font/Compose constraints; no service, adapter, provider or network fixture. */
@RunWith(AndroidJUnit4::class)
class DotForegroundLayoutTest {
    @Test fun natural208DpCareEntryKeepsEightDpInsideTheBottomMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val measured = ConcurrentHashMap<String, Rect>()
        val requestedHeight = AtomicInteger()
        var density = 0f
        DotTokenGalleryFixtureActivity.requestedFontScale = 1f
        try {
            ActivityScenario.launch<DotTokenGalleryFixtureActivity>(
                Intent(context, DotTokenGalleryFixtureActivity::class.java)
            ).use { scenario ->
                scenario.onActivity { activity ->
                    density = activity.resources.displayMetrics.density
                    assertEquals(1f, activity.resources.configuration.fontScale, 0f)
                    val intro = activity.getString(R.string.nm_care_intro)
                    activity.setContent { MinisTheme {
                        Box(Modifier.size(360.dp, 208.dp).onGloballyPositioned {
                            measured["panel"] = it.boundsInWindow()
                        }) {
                            DotForegroundChat(intro, {}, historyHeight = 80f, controlsHeight = 64f,
                                onContentHeightChanged = requestedHeight::set,
                                history = {
                                    LazyColumn(Modifier.fillMaxSize().onGloballyPositioned {
                                        measured["history"] = it.boundsInWindow()
                                    }, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                                        item { DotMessageBubble(false, Modifier.onGloballyPositioned {
                                            measured["bubble"] = it.boundsInWindow()
                                        }) {
                                            Text(intro, fontSize = DotTokens.Type.Care,
                                                lineHeight = DotTokens.Type.Care * 1.45f,
                                                modifier = Modifier.onGloballyPositioned {
                                                    measured["text"] = it.boundsInWindow()
                                                    val origin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                                                    measured["text_full"] = Rect(origin.x, origin.y,
                                                        origin.x + it.size.width, origin.y + it.size.height)
                                                })
                                        } }
                                    }
                                }, controls = {
                                    Row(Modifier.fillMaxWidth()) {
                                        DotDraftField("", {}, activity.getString(R.string.nm_care_goal),
                                            Modifier.weight(1f).onGloballyPositioned {
                                                measured["composer"] = it.boundsInWindow()
                                            })
                                        IconButton({}, Modifier.size(48.dp).onGloballyPositioned {
                                            measured["send"] = it.boundsInWindow()
                                        }) { Text("↑", fontSize = 16.sp) }
                                    }
                                })
                        }
                    } }
                }
                val deadline = SystemClock.uptimeMillis() + 5000
                while ((measured.size < 7 || requestedHeight.get() == 0) && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
                instrumentation.waitForIdleSync()
                val label = "natural Care entry measured=$measured requested=${requestedHeight.get()}"
                assertEquals(label, 7, measured.size)
                val panel = requireNotNull(measured["panel"])
                val history = requireNotNull(measured["history"])
                val bubble = requireNotNull(measured["bubble"])
                val text = requireNotNull(measured["text"])
                assertEquals("Natural height is not host-clamped $label", requestedHeight.get().toFloat(), panel.height, .5f)
                assertEquals("208dp reproduction $label", 208 * density, panel.height, .5f)
                assertEquals("Bubble top padding $label", 8 * density, text.top - bubble.top, .5f)
                assertEquals("Bubble bottom padding $label", 8 * density, bubble.bottom - text.bottom, .5f)
                assertEquals("Whole Care line $label", requireNotNull(measured["text_full"]).height, text.height, .5f)
                assertTrue("Bubble inside transcript $label", bubble.top >= history.top - .5f && bubble.bottom <= history.bottom + .5f)
                assertEquals("Transcript stays bottom aligned $label", history.bottom, bubble.bottom, .5f)
                assertTrue("Composer keeps 48dp $label", requireNotNull(measured["composer"]).height >= 48 * density - .5f)
                assertTrue("Send keeps 48dp $label", requireNotNull(measured["send"]).height >= 48 * density - .5f)
                println("FOREGROUND_MESSAGE_PADDING $label")
            }
        } finally { DotTokenGalleryFixtureActivity.requestedFontScale = 1f }
    }

    @Test fun transcriptAndFullTouchControlRemainVisibleInShortImeAndMovedUpFramesAtAllFontScales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            for (scale in listOf(1f, 1.5f, 2f)) {
                DotTokenGalleryFixtureActivity.requestedFontScale = scale
                ActivityScenario.launch<DotTokenGalleryFixtureActivity>(Intent(context, DotTokenGalleryFixtureActivity::class.java)).use { scenario ->
                    for (case in listOf("normal", "ime", "up")) for (user in listOf(true, false)) {
                        val measured = ConcurrentHashMap<String, Rect>()
                        var density = 0f
                        scenario.onActivity { activity ->
                            density = activity.resources.displayMetrics.density
                            assertEquals("Actual Android context font scale", scale, activity.resources.configuration.fontScale, 0f)
                            val host = RectPx(0f,0f,360*density,(if(case=="normal") 800f else if(case=="ime") 160f else 400f)*density)
                            val target = if(case=="up") RectPx(0f,200*density,360*density,400*density) else null
                            val frame = foregroundFrame(host,host.height*.55f,density,target)
                            if(case=="up") assertTrue(frame.movedUp)
                            activity.setContent { MinisTheme {
                                Box(Modifier.size((frame.bounds.width/density).dp,(frame.bounds.height/density).dp)) {
                                    DotForegroundChat("Current instruction", {}, capsule = {
                                        io.github.nanomuse.dot.ui.DotForegroundStatus(
                                            io.github.nanomuse.dot.DotForegroundState(connected = true,
                                                mode = io.github.nanomuse.dot.DotForegroundMode.Chat,
                                                operation = io.github.nanomuse.dot.DotForegroundOperation.Replying), {},
                                            Modifier.onGloballyPositioned { measured["control"] = it.boundsInWindow() })
                                    }, history = {
                                        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { measured["history"] = it.boundsInWindow() }) {
                                            item { DotMessageBubble(user) {
                                                Text(if(user) "User goal" else "Touch Search",fontSize=if(user) DotTokens.Type.Body else DotTokens.Type.Care,modifier=Modifier.onGloballyPositioned {
                                                    measured["text"] = it.boundsInWindow()
                                                    val origin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                                                    measured["text_full"] = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                                })
                                            } }

                                        }
                                    }, controls = {
                                        Row(Modifier.fillMaxWidth()) {
                                            DotDraftField("Retained first line\nRetained second line", {}, "Message",
                                                Modifier.weight(1f).onGloballyPositioned { measured["composer"] = it.boundsInWindow() })
                                            IconButton({}, Modifier.size(48.dp)) { Text("↑",fontSize=16.sp) }
                                        }
                                    })
                                }
                            } }
                        }
                        val deadline = SystemClock.uptimeMillis()+5000
                        while(measured.size<5 && SystemClock.uptimeMillis()<deadline) Thread.sleep(20)
                        val label="$case user=$user font=$scale measured=$measured"
                        assertEquals(label,5,measured.size)
                        assertTrue("Real production composer remains48dp $label", requireNotNull(measured["composer"]).height >= 48*density-.5f)
                        assertTrue("Visible transcript $label",requireNotNull(measured["history"]).height >= 48*density-.5f)
                        assertTrue("Visible 48dp status/Stop viewport $label",requireNotNull(measured["control"]).height >= 48*density-.5f)
                        val visibleText = requireNotNull(measured["text"])
                        val fullText = requireNotNull(measured["text_full"])
                        val viewport = requireNotNull(measured["history"])
                        assertEquals("Whole reply line height $label", fullText.height, visibleText.height, .5f)
                        assertTrue("Reply line inside history viewport $label", fullText.top >= viewport.top - .5f && fullText.bottom <= viewport.bottom + .5f)
                        assertTrue("Real message text $label",requireNotNull(measured["text"]).height >= (if(user) 16 else 18)*scale*density-.5f)
                        println("FOREGROUND_LAYOUT $label")
                    }
                }
            }
        } finally { DotTokenGalleryFixtureActivity.requestedFontScale = 1f }
    }
}
