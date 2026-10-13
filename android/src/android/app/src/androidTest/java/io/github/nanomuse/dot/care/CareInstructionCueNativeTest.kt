package io.github.nanomuse.dot.care

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import java.io.File
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nanomuse.dot.overlay.DotNativeServiceFixture
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.SceneKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Mounted framework focus View, native Canvas output and cleanup. Local fixture only. */
@RunWith(AndroidJUnit4::class)
class CareInstructionCueNativeTest {
    private val fixture = DotNativeServiceFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun connect() = fixture.connect()
    @After fun restore() = fixture.close()
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis()+5000
        while (SystemClock.uptimeMillis()<deadline) {
            var done=false
            instrumentation.runOnMainSync { done=condition() }
            if (done) return
            SystemClock.sleep(40)
        }
        fail(message)
    }
    @Test fun cueDrawsSeparateFromTargetAndCardAndClearRemovesItImmediately() {
        lateinit var layer: CareFocusLayer
        var created=false
        lateinit var target: RectPx
        lateinit var card: RectPx
        lateinit var host: RectPx
        val scene=SceneKey("native-cue-fixture",1,0,1)
        try {
            instrumentation.runOnMainSync {
                val service=fixture.service()
                val metrics=service.resources.displayMetrics
                host=RectPx(0f,0f,metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat())
                target=RectPx(host.width*.35f,host.height*.40f,host.width*.65f,host.height*.45f)
                card=RectPx(20f,host.height*.8f,host.width-20f,host.bottom-20f)
                layer=CareFocusLayer(service); created=true
                layer.show(target,"step1",scene)
            }
            await("Focus must mount in the real WindowManager") { layer.view?.let { it.isAttachedToWindow && it.width>0 && it.height>0 }==true }
            instrumentation.runOnMainSync {
                val view=requireNotNull(layer.view)
                val params=view.layoutParams as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                layer.setInstruction("Tap Search",card,host,"step1",scene)
            }
            await("Native Canvas must contain a separate cue, without ink over target content or card") {
                val view=requireNotNull(layer.view)
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(image))
                    val location=IntArray(2); view.getLocationOnScreen(location)
                    var cueInk=0
                    var firstCueY=Int.MAX_VALUE; var lastCueY=Int.MIN_VALUE
                    val edge=5f*fixture.service().resources.displayMetrics.density
                    for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
                        if (Color.alpha(image.getPixel(x,y))==0) continue
                        val sx=x+location[0]; val sy=y+location[1]
                        assertFalse("Cue must not draw over the chat card",sx>=card.left && sx<card.right && sy>=card.top && sy<card.bottom)
                        val inTarget=sx>=target.left && sx<target.right && sy>=target.top && sy<target.bottom
                        if (inTarget) assertFalse("Only the target border may draw inside target",sx>target.left+edge && sx<target.right-edge && sy>target.top+edge && sy<target.bottom-edge)
                        else { cueInk++; firstCueY=minOf(firstCueY,sy); lastCueY=maxOf(lastCueY,sy) }
                    }
                    if (cueInk>100) assertTrue("A short instruction stays local; no leader extends it to the bottom card",
                        lastCueY-firstCueY < host.height*.25f)
                    cueInk>100
                } finally { image.recycle() }
            }
            instrumentation.runOnMainSync {
                val oldView=requireNotNull(layer.view)
                layer.clear()
                assertNull(layer.view); assertNull(layer.bounds); assertFalse(oldView.isAttachedToWindow)
                layer.setInstruction("Stale",card,host,"step1",scene)
                layer.replayInstruction("step1",scene)
                assertNull(layer.view)
            }
        } finally { instrumentation.runOnMainSync { if (created) layer.clear() } }
    }

    private data class CueFrame(val bitmap: Bitmap, val time: Long, val cueInk: Int,
        val textInk: Int, val targetInk: Int) : AutoCloseable {
        override fun close() = bitmap.recycle()
    }
    /** Render the actual mounted native View; this is isolated Canvas evidence, not a display screenshot. */
    private fun capture(view: View, target: RectPx, card: RectPx): CueFrame {
        lateinit var bitmap: Bitmap
        var time=0L
        val location=IntArray(2)
        instrumentation.runOnMainSync {
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.getLocationOnScreen(location)
            time=SystemClock.uptimeMillis()
            view.draw(Canvas(bitmap))
        }
        var cue=0; var text=0; var outline=0
        val tolerance=5f*fixture.service().resources.displayMetrics.density
        try {
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val alpha=Color.alpha(bitmap.getPixel(x,y))
                if (alpha==0) continue
                val sx=x+location[0]; val sy=y+location[1]
                assertFalse("Frame must not draw over the bottom card",sx>=card.left && sx<card.right && sy>=card.top && sy<card.bottom)
                val nearTarget=sx>=target.left-tolerance && sx<target.right+tolerance &&
                    sy>=target.top-tolerance && sy<target.bottom+tolerance
                if (nearTarget) outline++ else {
                    cue++
                    // The glass (224 alpha) and its translucent border stay below this
                    // threshold; opaque native glyph interiors supply these pixels.
                    if (alpha>=248) text++
                }
            }
            return CueFrame(bitmap,time,cue,text,outline)
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
    private fun awaitFrame(view: View, target: RectPx, card: RectPx, label: String,
        timeout: Long=5000, condition: (CueFrame)->Boolean): CueFrame {
        val end=SystemClock.uptimeMillis()+timeout
        var last="no frame"
        while (SystemClock.uptimeMillis()<end) {
            val frame=capture(view,target,card)
            last="time=${frame.time} cue=${frame.cueInk} text=${frame.textInk} outline=${frame.targetInk}"
            if (condition(frame)) return frame
            frame.close(); SystemClock.sleep(60)
        }
        throw AssertionError("$label; last $last")
    }
    private fun retain(directory: File, label: String, frame: CueFrame, started: Long) {
        File(directory,"$label.png").outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        File(directory,"frames.tsv").appendText("$label\t${frame.time}\t${frame.time-started}\t${frame.cueInk}\t${frame.textInk}\t${frame.targetInk}\n")
        println("CARE_CUE_NATIVE_CANVAS frame=$label elapsedMs=${frame.time-started} cueInk=${frame.cueInk} textInk=${frame.textInk} path=${File(directory,"$label.png")}")
    }

    @Test fun elapsedCueRevealsExpiresDoesNotRestartOnRefreshAndReplaysOnlyExplicitly() {
        lateinit var layer: CareFocusLayer
        var created=false
        lateinit var target: RectPx
        lateinit var card: RectPx
        lateinit var host: RectPx
        lateinit var view: View
        val scene=SceneKey("native-cue-lifecycle",1,0,1)
        val text="Tap Search then type"
        val directory=File(fixture.service().cacheDir,"care-cue-native-lifecycle").apply { mkdirs() }
        File(directory,"frames.tsv").writeText("# Mounted CareFocusLayer native Canvas fixture, not a display screenshot or provider result\nframe\tuptimeMs\telapsedMs\tcueInk\ttextInk\ttargetInk\n")
        try {
            instrumentation.runOnMainSync {
                val service=fixture.service()
                assertTrue("Run this typing-lifecycle gate with Android motion enabled",
                    Settings.Global.getFloat(service.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f)
                val metrics=service.resources.displayMetrics
                host=RectPx(0f,0f,metrics.widthPixels.toFloat(),metrics.heightPixels.toFloat())
                target=RectPx(host.width*.35f,host.height*.40f,host.width*.65f,host.height*.45f)
                card=RectPx(20f,host.height*.8f,host.width-20f,host.bottom-20f)
                layer=CareFocusLayer(service); created=true
                layer.show(target,"step1",scene)
            }
            await("Lifecycle focus must attach before timing") { layer.view?.let { it.isAttachedToWindow && it.width>0 && it.height>0 }==true }
            // First instruction starts only after the real View has mounted. No fake clock is installed.
            var started=0L
            instrumentation.runOnMainSync {
                view=requireNotNull(layer.view)
                started=SystemClock.uptimeMillis()
                layer.setInstruction(text,card,host,"step1",scene)
            }
            var partialGlyphs=0
            awaitFrame(view,target,card,"Cue must actually reveal initial glyphs",1500) { it.cueInk>100 && it.textInk>0 }.use {
                partialGlyphs=it.textInk; retain(directory,"01-revealing",it,started)
            }
            awaitFrame(view,target,card,"Later reveal must contain more native glyph ink",2200) {
                it.time-started>=1100 && it.textInk>partialGlyphs+20
            }.use { retain(directory,"02-full-hold",it,started); assertTrue(it.targetInk>0) }
            awaitFrame(view,target,card,"Cue must disappear on real elapsed time",5000) {
                it.cueInk==0 && it.targetInk>0
            }.use { retain(directory,"03-expired",it,started); assertTrue("Cue had an actual readable lifetime",it.time-started>=2400) }

            // Unchanged scene/step layout updates must not re-admit the expired decoration.
            repeat(3) {
                instrumentation.runOnMainSync {
                    layer.show(target,"step1",scene)
                    layer.setInstruction(text,card,host,"step1",scene)
                }
                SystemClock.sleep(100)
                capture(view,target,card).use { frame ->
                    assertEquals("Refresh must not restart cue ink",0,frame.cueInk)
                    assertTrue(frame.targetInk>0)
                    if (it==2) retain(directory,"04-refresh-stays-expired",frame,started)
                }
            }
            var replayed=0L
            instrumentation.runOnMainSync { replayed=SystemClock.uptimeMillis(); layer.replayInstruction("step1",scene) }
            awaitFrame(view,target,card,"Explicit current-step Replay must restore cue ink",1500) {
                it.cueInk>100 && it.textInk>0
            }.use { retain(directory,"05-explicit-replay",it,replayed) }
            awaitFrame(view,target,card,"The replay must expire too, without an infinite animation",5000) {
                it.cueInk==0 && it.targetInk>0
            }.use { retain(directory,"06-replay-expired",it,replayed) }

            // Clear during another active reveal; pending animation and expiry must not revive it.
            instrumentation.runOnMainSync { layer.replayInstruction("step1",scene) }
            awaitFrame(view,target,card,"Need an active replay before cancellation",1500) { it.cueInk>100 }.close()
            instrumentation.runOnMainSync {
                layer.clear(); assertNull(layer.view); assertFalse(view.isAttachedToWindow)
                layer.setInstruction(text,card,host,"step1",scene)
                layer.replayInstruction("step1",scene)
                assertNull(layer.view)
            }
            SystemClock.sleep(250)
            capture(view,target,card).use {
                assertEquals(0,it.cueInk); assertEquals(0,it.targetInk)
                retain(directory,"07-cleared-no-late-frame",it,started)
            }
        } finally { instrumentation.runOnMainSync { if (created) layer.clear() } }
    }

}
