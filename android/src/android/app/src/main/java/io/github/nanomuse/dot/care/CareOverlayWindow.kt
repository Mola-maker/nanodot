package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
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
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.overlay.DotOverlayOwner
import io.github.nanomuse.dot.overlay.isUsable
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
    private var menuDismiss: (() -> Boolean)? = null
    private var params: WindowManager.LayoutParams?=null
    private var removeBack: (() -> Unit)?=null
    private var removeGeometry: (() -> Unit)?=null
    private var requestedHeight=0
    private var maxHeight=0
    private var instructionId: String?=null
    private val placement = mutableStateOf(ForegroundPlacement.Bottom)
    private var placementTarget: RectPx? = null
    private var expanded = false
    private var adjustment: Pair<Float, Float>? = null
    private fun layoutViewport(): RectPx? {
        val host = viewport()?.takeIf { it.isUsable() } ?: return null
        if (Build.VERSION.SDK_INT < 30) return host
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val display = metrics.bounds
        return careSafeViewport(host, RectPx((display.left+insets.left).toFloat(),(display.top+insets.top).toFloat(),
            (display.right-insets.right).toFloat(),(display.bottom-insets.bottom).toFloat()))
    }
    private fun frame(host: RectPx, height: Float, density: Float, target: RectPx?,
        position: Pair<Float, Float>? = adjustment, width: Int? = null, pixels: Int? = null): ForegroundFrame =
        careWindowFrame(host, height, density, target, expanded, position,
            (156f + 48f * (service.resources.configuration.fontScale - 1f).coerceAtLeast(0f)) * density, width, pixels)
    private fun expandCard() { expanded = true; adjustment = null; refreshGeometry() }
    private fun adjustCard() {
        val host = layoutViewport() ?: return
        val p = params ?: return
        moveCard(0f, if (p.y < host.top + host.height / 2f) host.height else -host.height)
    }
    private fun moveCard(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        val host = layoutViewport() ?: return
        val view = card ?: return
        val p = params ?: return
        if (!view.isAttachedToWindow) return
        if (!host.isUsable()) return
        val proposedX = (p.x + dx - host.left) / (host.width - p.width).coerceAtLeast(1f)
        val proposedY = (p.y + dy - host.top) / (host.height - p.height).coerceAtLeast(1f)
        val fitted = frame(host, p.height.toFloat(), service.resources.displayMetrics.density, placementTarget,
            proposedX to proposedY, p.width, p.height)
        p.x = fitted.bounds.left.roundToInt(); p.y = fitted.bounds.top.roundToInt()
        adjustment = (if (host.width > p.width) (p.x - host.left) / (host.width - p.width) else 0f) to
            (if (host.height > p.height) (p.y - host.top) / (host.height - p.height) else 0f)
        windows.updateViewLayout(view, p)
        updateInstruction()
    }
    private fun updateInstruction() {
        val view = card ?: return
        val key = instructionId ?: return
        val currentScene = scene()
        val host = layoutViewport() ?: return
        val proposal = session.controller.state.value.proposal ?: return
        if (key != session.instructionKey() || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0 ||
            session.focusBounds(currentScene) == null) return
        val location = IntArray(2); view.getLocationOnScreen(location)
        val measuredCard = RectPx(location[0].toFloat(),location[1].toFloat(),
            (location[0]+view.width).toFloat(),(location[1]+view.height).toFloat())
        focus.setInstruction(proposal.instruction, measuredCard, host, key, currentScene)
    }
    private val job=scope.launch {
        combine(session.controller.state,session.visible,session.editingGoal) { _,_,_ -> Unit }.collect { render() }
    }
    fun clearFocus() { focus.clear(); registry?.setForegroundTarget(null) }
    fun hide() { clearFocus(); removeCard(); registry?.setCareActive(false) }
    fun stopSpeech() = Unit // Repeat is local visual playback; no speech provider is allocated.
    fun refreshGeometry() { if(session.visible.value) render() }
    @Suppress("DEPRECATION") // Direct Back support on API26–32 also needs keyboard-first behavior.
    private fun hideImeOrClose(view: View) {
        if (card !== view) return
        if (registry?.dismissOrbMenu() == true) return
        if (menuDismiss?.invoke() == true) return
        val observed = service.windows
        val keyboard = try { observed.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            finally { observed.forEach { it.recycle() } }
        if (keyboard) service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        else session.pause(hide = true)
    }
    private fun render() {
        session.observeConversation()
        val host=layoutViewport()
        if(!session.visible.value || host==null || !host.isUsable()) {
            clearFocus(); removeCard(); registry?.setCareActive(false); return
        }
        registry?.setCareActive(true)
        val target=session.focusBounds(scene())
        if (placementTarget != target) { placementTarget = target; expanded = false; adjustment = null }
        registry?.setForegroundTarget(target)
        val nextId=if(target==null) null else session.instructionKey()
        instructionId = nextId
        if(target==null) focus.clear() else focus.show(target, nextId.orEmpty(), scene())
        val density=service.resources.displayMetrics.density
        val initial = frame(host, requestedHeight.takeIf { it > 0 }?.toFloat() ?: (220f * density), density, target)
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
            val nextHeight=minOf(view.measuredHeight,maxHeight).coerceAtLeast(1)
            val fitted=frame(host,nextHeight.toFloat(),density,target, width=width, pixels=nextHeight)
            placement.value = fitted.placement
            val nextX=fitted.bounds.left.roundToInt(); val nextY=fitted.bounds.top.roundToInt()
            // Layout callbacks also refresh content/instruction geometry. Reapplying the
            // same native frame would request another layout and feed that callback forever.
            if (p.width != width || p.height != nextHeight || p.x != nextX || p.y != nextY) {
                p.width=width; p.height=nextHeight; p.x=nextX; p.y=nextY
                windows.updateViewLayout(view,p)
            }
            updateInstruction()
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
                CarePanel(session,::copy, onMenuDismissHandler = { menuDismiss = it }, placementCompact = placement.value == ForegroundPlacement.Compact,
                    onExpand = ::expandCard, onAdjust = ::adjustCard, onDrag = ::moveCard, capsule = { careStatus ->
                    io.github.nanomuse.dot.ui.DotForegroundStatus(evidence, {
                        onStop?.invoke() ?: session.pause(hide = true)
                    }, fallbackDescription = careStatus)
                },onRepeat={
                if (session.focusBounds(scene()) != null) focus.replayInstruction(session.instructionKey(), scene())
            }, onContentHeightChanged = { height ->
                if (height > 0 && requestedHeight != height) {
                    requestedHeight = height
                    view.post { if (card === view) refreshGeometry() }
                }
            }) } } }
        }
        view.addView(content,FrameLayout.LayoutParams(-1,-2))
        val p=WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="Nanodot Care card"
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= 30) {
                // layoutViewport already clips bars/cutouts/IME in screen coordinates.
                // Native fitting must not translate or clamp the same rectangle again.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
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
        instructionId=null; requestedHeight=0
        placementTarget = null; expanded = false; adjustment = null; placement.value = ForegroundPlacement.Bottom
        stopSpeech()
    }
    fun close() {
        job.cancel(); clearFocus(); removeCard()
        registry?.setCareActive(false)
    }
}
