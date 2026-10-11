package io.github.nanomuse.dot.chat

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.overlay.foregroundFrame
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.ui.DotTokenGalleryFixtureActivity
import io.github.nanomuse.dot.ui.DotTokens
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/** Real Android font/Compose constraints; no service, adapter, provider or network fixture. */
@RunWith(AndroidJUnit4::class)
class DotForegroundLayoutTest {
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
                                    DotForegroundChat("Current instruction", {}, history = {
                                        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { measured["history"] = it.boundsInWindow() }) {
                                            item { DotMessageBubble(user) {
                                                Text(if(user) "User goal" else "Touch Search",fontSize=if(user) DotTokens.Type.Body else DotTokens.Type.Care,modifier=Modifier.onGloballyPositioned { measured["text"] = it.boundsInWindow() })
                                            } }
                                            item { DotMessageBubble(false) { Text("Actual instruction",fontSize=DotTokens.Type.Care) } }
                                        }
                                    }, controls = {
                                        Column(Modifier.fillMaxWidth()) {
                                            Button({},Modifier.fillMaxWidth().heightIn(min=48.dp).onGloballyPositioned { measured["control"] = it.boundsInWindow() }) { Text("Start") }
                                            OutlinedTextField("Retained draft",{},Modifier.fillMaxWidth())
                                            Button({},Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("End") }
                                        }
                                    })
                                }
                            } }
                        }
                        val deadline = SystemClock.uptimeMillis()+5000
                        while(measured.size<3 && SystemClock.uptimeMillis()<deadline) Thread.sleep(20)
                        val label="$case user=$user font=$scale measured=$measured"
                        assertEquals(label,3,measured.size)
                        assertTrue("Visible transcript $label",requireNotNull(measured["history"]).height >= 48*density-.5f)
                        assertTrue("Whole 48dp touch viewport $label",requireNotNull(measured["control"]).height >= 48*density-.5f)
                        assertTrue("Real message text $label",requireNotNull(measured["text"]).height >= (if(user) 16 else 18)*scale*density-.5f)
                        println("FOREGROUND_LAYOUT $label")
                    }
                }
            }
        } finally { DotTokenGalleryFixtureActivity.requestedFontScale = 1f }
    }
}
