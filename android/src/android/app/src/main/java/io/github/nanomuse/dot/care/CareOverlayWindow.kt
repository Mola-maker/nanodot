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
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import io.github.nanomuse.dot.overlay.ForegroundPlacement
import io.github.nanomuse.dot.overlay.ForegroundFrame
import io.github.nanomuse.dot.DotForegroundState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.overlay.DotOverlayOwner
import io.github.nanomuse.dot.overlay.foregroundFrame
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.SceneKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Measured, bounded card window; only the card rectangle consumes touches. */
class CareOverlayWindow(private val service: AccessibilityService, scope: CoroutineScope,
    private val session: CareSession, private val viewport: () -> RectPx?, private val scene: () -> SceneKey?,
    private val foreground: StateFlow<DotForegroundState>? = null, private val onStop: (() -> Unit)? = null) {
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
    private var removeGeometry: (() -> Unit)?=null
    private var player: ReadAloudPlayer?=null
    private var maxHeight=0
    private var instructionBounds: RectPx?=null
    private var instructionId: String?=null
    private val placement = mutableStateOf(ForegroundPlacement.Bottom)
    private var placementTarget: RectPx? = null
    private var expanded = false
    private var adjustment: Pair<Float, Float>? = null
    private fun frame(host: RectPx, height: Float, density: Float, target: RectPx?): ForegroundFrame {
        var fitted = foregroundFrame(host, height, density, if (expanded) null else target)
        if (fitted.placement == ForegroundPlacement.Compact) {
            // Keep the fallback small enough to expose more of the host and retain the existing
            // 48dp reply/control budget. Expand is an explicit, reversible user choice.
            fitted = foregroundFrame(host, minOf(height, 156f * density), density, target)
            adjustment?.let { (x, y) ->
                val b = fitted.bounds
                val left = host.left + (host.width - b.width).coerceAtLeast(0f) * x.coerceIn(0f, 1f)
                val top = host.top + (host.height - b.height).coerceAtLeast(0f) * y.coerceIn(0f, 1f)
                fitted = fitted.copy(bounds = RectPx(left, top, left + b.width, top + b.height))
            }
        }
        return fitted
    }
    private fun expandCard() { expanded = true; adjustment = null; refreshGeometry() }
    private fun adjustCard() {
        val host = viewport() ?: return
        val p = params ?: return
        moveCard(0f, if (p.y < host.top + host.height / 2f) host.height else -host.height)
    }
    private fun moveCard(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        val host = viewport() ?: return
        val view = card ?: return
        val p = params ?: return
        if (!view.isAttachedToWindow) return
        p.x = (p.x + dx).roundToInt().coerceIn(host.left.roundToInt(), (host.right - p.width).roundToInt().coerceAtLeast(host.left.roundToInt()))
        p.y = (p.y + dy).roundToInt().coerceIn(host.top.roundToInt(), (host.bottom - p.height).roundToInt().coerceAtLeast(host.top.roundToInt()))
        adjustment = (if (host.width > p.width) (p.x - host.left) / (host.width - p.width) else 0f) to
            (if (host.height > p.height) (p.y - host.top) / (host.height - p.height) else 0f)
        windows.updateViewLayout(view, p)
        updateLeader()
    }
    private fun updateLeader() {
        val view=card ?: return
        val r=instructionBounds
        val key=instructionId
        val host=viewport()
        if(r==null || key==null || key!=session.instructionKey() || host==null ||
            !view.isAttachedToWindow || view.width<=0 || view.height<=0 || session.focusBounds(scene())==null) {
            focus.setInstructionBubble(null); return
        }
        val location=IntArray(2); view.getLocationOnScreen(location)
        val measuredCard=RectPx(location[0].toFloat(),location[1].toFloat(),
            (location[0]+view.width).toFloat(),(location[1]+view.height).toFloat())
        focus.setInstructionBubble(RectPx(r.left+location[0],r.top+location[1],r.right+location[0],r.bottom+location[1]),
            measuredCard,host,key)
    }
    private val job=scope.launch {
        combine(session.controller.state,session.visible,session.editingGoal) { _,_,_ -> Unit }.collect { render() }
    }
    fun clearFocus() { focus.clear(); registry?.setForegroundTarget(null) }
    fun hide() { clearFocus(); removeCard(); registry?.setCareActive(false) }
    fun stopSpeech() { player?.stop() }
    fun refreshGeometry() { if(session.visible.value) render() }
    @Suppress("DEPRECATION") // Direct Back support on API26–32 also needs keyboard-first behavior.
    private fun hideImeOrClose(view: View) {
        if (card !== view) return
        val observed = service.windows
        val keyboard = try { observed.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            finally { observed.forEach { it.recycle() } }
        if (keyboard) service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        else session.pause(hide = true)
    }
    private fun render() {
        session.observeConversation()
        val host=viewport()
        if(!session.visible.value || host==null || host.width<=0 || host.height<=0) {
            clearFocus(); removeCard(); registry?.setCareActive(false); return
        }
        registry?.setCareActive(true)
        val target=session.focusBounds(scene())
        if (placementTarget != target) { placementTarget = target; expanded = false; adjustment = null }
        registry?.setForegroundTarget(target)
        val nextId=if(target==null) null else session.instructionKey()
        if(nextId!=instructionId) { instructionId=nextId; instructionBounds=null; focus.setInstructionBubble(null) }
        if(target==null) focus.clear() else focus.show(target, nextId.orEmpty())
        val density=service.resources.displayMetrics.density
        val initial = frame(host, host.height*.55f, density, target)
        placement.value = initial.placement
        val room=initial.bounds
        val width=room.width.roundToInt()
        maxHeight=room.height.roundToInt()
        if(card==null) addCard()
        val view=card ?: return
        // addView returns before ViewRoot attaches its ComposeView. Measuring here
        // would resolve a window recomposer before that window exists.
        if(!view.isAttachedToWindow) return
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
        params?.let { p ->
            p.width=width; p.height=minOf(view.measuredHeight,maxHeight).coerceAtLeast(1)
            val fitted=frame(host,p.height.toFloat(),density,target)
            placement.value = fitted.placement
            p.x=fitted.bounds.left.roundToInt(); p.y=fitted.bounds.top.roundToInt()
            windows.updateViewLayout(view,p)
            updateLeader()
        }
    }
    @Suppress("DEPRECATION")
    private fun addCard() {
        val lifecycle=DotOverlayOwner()
        val view=object: FrameLayout(service) {
            private var backDown = false
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(maxHeight.coerceAtLeast(1),View.MeasureSpec.AT_MOST))
            }
            private fun back(e: KeyEvent): Boolean {
                if(e.keyCode!=KeyEvent.KEYCODE_BACK) return false
                if(e.action==KeyEvent.ACTION_DOWN) { backDown=true; return true }
                if(e.action==KeyEvent.ACTION_UP && backDown) {
                    backDown=false; hideImeOrClose(this); return true
                }
                return false
            }
            override fun dispatchKeyEventPreIme(e: KeyEvent)=back(e)||super.dispatchKeyEventPreIme(e)
            override fun dispatchKeyEvent(e: KeyEvent)=back(e)||super.dispatchKeyEvent(e)
        }
        view.setViewTreeLifecycleOwner(lifecycle); view.setViewTreeSavedStateRegistryOwner(lifecycle)
        val content=ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MinisTheme { io.github.nanomuse.dot.ui.DotMotionHost {
                val evidence by (this@CareOverlayWindow.foreground ?: registry?.foregroundState ?: kotlinx.coroutines.flow.MutableStateFlow(io.github.nanomuse.dot.DotForegroundState())).collectAsState()
                CarePanel(session,::copy, placementCompact = placement.value == ForegroundPlacement.Compact,
                    onExpand = ::expandCard, onAdjust = ::adjustCard, onDrag = ::moveCard, capsule = {
                    io.github.nanomuse.dot.ui.DotForegroundStatus(evidence, {
                        onStop?.invoke() ?: session.pause(hide = true)
                    })
                },onRepeat={ instruction ->
                val speaker=player ?: ReadAloudPlayer(service).also { player=it }
                speaker.speak(instruction)
            },onInstructionBounds={ id,bounds ->
                if(card===view && id==instructionId) {
                    instructionBounds=bounds
                    if(bounds==null) focus.setInstructionBubble(null) else updateLeader()
                }
            }) } } }
        }
        view.addView(content,FrameLayout.LayoutParams(-1,-2))
        val p=WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="Nanodot Care card"
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        card=view; composition=content; owner=lifecycle; params=p
        val refresh=Runnable { if(card===view && view.isAttachedToWindow) refreshGeometry() }
        val attach=object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { view.post(refresh) }
            override fun onViewDetachedFromWindow(v: View) { view.removeCallbacks(refresh) }
        }
        view.addOnAttachStateChangeListener(attach)
        removeGeometry={ view.removeCallbacks(refresh); view.removeOnAttachStateChangeListener(attach) }
        try { windows.addView(view,p); registry?.registerCareWindow(view); lifecycle.start() }
        catch(failure: RuntimeException) {
            removeGeometry?.invoke(); removeGeometry=null
            card=null; composition=null; owner=null; params=null
            content.disposeComposition(); lifecycle.destroy(); throw failure
        }
        view.setOnApplyWindowInsetsListener { _,insets -> view.post(refresh); insets }
        view.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            // Compose content/font changes can change wrap height without a host event.
            view.post(refresh)
        }
        if(Build.VERSION.SDK_INT>=33) view.post {
            if(card===view && view.isAttachedToWindow) {
                val dispatcher=view.findOnBackInvokedDispatcher()
                val callback=android.window.OnBackInvokedCallback { hideImeOrClose(view) }
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
        removeGeometry?.invoke(); removeGeometry=null
        card?.let { registry?.unregisterCareWindow(it); windows.removeViewImmediate(it) }
        composition?.disposeComposition(); owner?.destroy()
        card=null; composition=null; owner=null; params=null
        instructionBounds=null; instructionId=null
        placementTarget = null; expanded = false; adjustment = null; placement.value = ForegroundPlacement.Bottom
        stopSpeech()
    }
    fun close() {
        job.cancel(); clearFocus(); removeCard(); player?.shutdown(); player=null
        registry?.setCareActive(false)
    }
}
