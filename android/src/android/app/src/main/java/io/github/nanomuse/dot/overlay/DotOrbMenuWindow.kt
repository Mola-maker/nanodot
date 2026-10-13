package io.github.nanomuse.dot.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.ui.DotMotionHost
import io.github.nanomuse.dot.voice.DotVoiceController
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** A transient presentation owner; admission and microphone capabilities remain in the coordinator. */
internal class DotOrbMenuWindow(private val service: AccessibilityService, private val action: (DotIntent) -> Unit) {
    private val windows = service.getSystemService(WindowManager::class.java)
    var view: View? = null
        private set
    var bounds: RectPx? = null
        private set
    private var owner: DotOverlayOwner? = null
    private var composition: ComposeView? = null
    private var generation = 0L
    private var windowAdded = false

    fun show(frame: DotOrbMenuFrame, voice: StateFlow<DotVoiceController.Ui>): Boolean {
        checkMain()
        dismiss()
        val ticket = generation
        val lifecycle = DotOverlayOwner()
        val root = object : FrameLayout(service) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    return false // NOT_TOUCH_MODAL leaves the original touch with its host.
                }
                return super.dispatchTouchEvent(event)
            }
        }
        root.setViewTreeLifecycleOwner(lifecycle)
        root.setViewTreeSavedStateRegistryOwner(lifecycle)
        val content = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MinisTheme { DotMotionHost {
                DotOrbMenu(frame, voice) { intent ->
                    if (generation == ticket && view === root) {
                        dismiss() // Revoke every action before effects or animation can re-enter.
                        action(intent)
                    }
                }
            } } }
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val params = WindowManager.LayoutParams(frame.bounds.width.roundToInt(), frame.bounds.height.roundToInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = frame.bounds.left.roundToInt(); y = frame.bounds.top.roundToInt()
            title = "Nanodot Dot actions"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            // The planner already clips to the host's safe/IME region in screen coordinates.
            // Do not apply status-bar insets a second time to this bounded window.
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Publish ownership before WindowManager can emit accessibility events for attachment.
        view = root; bounds = frame.bounds; owner = lifecycle; composition = content
        return try { windows.addView(root, params); windowAdded = true; lifecycle.start(); true }
        catch (_: RuntimeException) { dismiss(); false }
    }

    fun dismiss(): Boolean {
        checkMain()
        ++generation
        val previous = view
        val previousOwner = owner
        val previousComposition = composition
        val remove = windowAdded
        windowAdded = false
        view = null; bounds = null; owner = null; composition = null
        try { if (previous != null && remove) windows.removeViewImmediate(previous) }
        finally { previousComposition?.disposeComposition(); previousOwner?.destroy() }
        return previous != null
    }

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
}
