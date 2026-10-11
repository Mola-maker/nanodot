package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.overlay.DotOverlayOwner
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.SceneKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Measured, bounded card window; only the card rectangle consumes touches. */
class CareOverlayWindow(private val service: AccessibilityService, scope: CoroutineScope,
    private val session: CareSession, private val viewport: () -> RectPx?, private val scene: () -> SceneKey?) {
    private val windows=service.getSystemService(WindowManager::class.java)
    private val registry get()=(service as? MinisAccessibilityService)?.dotOverlay
    private var focusView: View?=null
    val focus=CareFocusLayer(service) { next ->
        focusView?.let { registry?.unregisterCareWindow(it) }
        focusView=next; next?.let { registry?.registerCareWindow(it) }
    }
    private var card: FrameLayout?=null
    private var composition: ComposeView?=null
    private var owner: DotOverlayOwner?=null
    private var params: WindowManager.LayoutParams?=null
    private var removeBack: (() -> Unit)?=null
    private var player: ReadAloudPlayer?=null
    private var maxHeight=0
    private val job=scope.launch {
        combine(session.controller.state,session.visible,session.editingGoal) { _,_,_ -> Unit }.collect { render() }
    }
    fun clearFocus() = focus.clear()
    fun hide() { focus.clear(); removeCard(); registry?.setCareActive(false) }
    fun stopSpeech() { player?.stop() }
    fun refreshGeometry() { if(session.visible.value) render() }
    private fun render() {
        val host=viewport()
        if(!session.visible.value || host==null || host.width<=0 || host.height<=0) {
            focus.clear(); removeCard(); registry?.setCareActive(false); return
        }
        registry?.setCareActive(true)
        val target=session.focusBounds(scene())
        if(target==null) focus.clear() else focus.show(target)
        if(card==null) addCard()
        val density=service.resources.displayMetrics.density
        val width=minOf(host.width.roundToInt(),(360*density).roundToInt())
        // Prefer the side of the target with more room so its actual touch region stays exposed.
        val above=target?.let { (it.top-host.top).coerceAtLeast(0f) } ?: 0f
        val below=target?.let { (host.bottom-it.bottom).coerceAtLeast(0f) } ?: host.height
        val atBottom=below>=above
        maxHeight=careCardMaxHeight(host,target,density)
        val view=card ?: return
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
        params?.let { p ->
            p.width=width; p.height=minOf(view.measuredHeight,maxHeight).coerceAtLeast(1)
            p.x=(host.left+(host.width-width)/2).roundToInt()
            p.y=if(atBottom) host.bottom.roundToInt()-p.height else host.top.roundToInt()
            windows.updateViewLayout(view,p)
        }
    }
    @Suppress("DEPRECATION")
    private fun addCard() {
        val lifecycle=DotOverlayOwner()
        val view=object: FrameLayout(service) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(maxHeight.coerceAtLeast(1),View.MeasureSpec.AT_MOST))
            }
            private fun back(e: KeyEvent): Boolean {
                if(e.keyCode!=KeyEvent.KEYCODE_BACK) return false
                if(e.action==KeyEvent.ACTION_UP) session.pause(hide=true)
                return true
            }
            override fun dispatchKeyEventPreIme(e: KeyEvent)=back(e)||super.dispatchKeyEventPreIme(e)
            override fun dispatchKeyEvent(e: KeyEvent)=back(e)||super.dispatchKeyEvent(e)
        }
        view.setViewTreeLifecycleOwner(lifecycle); view.setViewTreeSavedStateRegistryOwner(lifecycle)
        val content=ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MinisTheme { CarePanel(session,::copy) { instruction ->
                val speaker=player ?: ReadAloudPlayer(service).also { player=it }
                speaker.speak(instruction)
            } } }
        }
        view.addView(content,FrameLayout.LayoutParams(-1,-2))
        val p=WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="Nanodot Care card"
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        card=view; composition=content; owner=lifecycle; params=p
        try { windows.addView(view,p); registry?.registerCareWindow(view); lifecycle.start() }
        catch(failure: RuntimeException) {
            card=null; composition=null; owner=null; params=null
            content.disposeComposition(); lifecycle.destroy(); throw failure
        }
        view.setOnApplyWindowInsetsListener { _,insets -> view.post { refreshGeometry() }; insets }
        view.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            // Compose content/font changes can change wrap height without a host event.
            view.post { if(card===view) refreshGeometry() }
        }
        if(Build.VERSION.SDK_INT>=33) view.post {
            if(card===view) {
                val dispatcher=view.findOnBackInvokedDispatcher()
                val callback=android.window.OnBackInvokedCallback { session.pause(hide=true) }
                dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
                removeBack={ dispatcher?.unregisterOnBackInvokedCallback(callback) }
            }
        }
    }
    /** Only a user button invokes clipboard writing. It does not confirm or advance a step. */
    private fun copy() {
        val state=session.controller.state.value
        if(session.focusBounds(scene())==null) return
        val text=state.proposal?.copyText ?: return
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(service.getString(R.string.nm_care_copy),text))
    }
    private fun removeCard() {
        removeBack?.invoke(); removeBack=null
        card?.let { registry?.unregisterCareWindow(it); windows.removeViewImmediate(it) }
        composition?.disposeComposition(); owner?.destroy()
        card=null; composition=null; owner=null; params=null
        stopSpeech()
    }
    fun close() {
        job.cancel(); focus.clear(); removeCard(); player?.shutdown(); player=null
        registry?.setCareActive(false)
    }
}
