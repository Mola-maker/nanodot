package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.ui.DotTokens

/** One visual target. Never intercepts input or owns accessibility focus. */
class CareFocusLayer(private val service: AccessibilityService, private val changed: (View?) -> Unit = {}) {
    private val windows=service.getSystemService(WindowManager::class.java)
    var bounds: RectPx?=null
        private set
    var view: View?=null
        private set
    private var animation: ValueAnimator?=null
    fun show(bounds: RectPx) {
        if(bounds.width<=0 || bounds.height<=0 || !listOf(bounds.left,bounds.top,bounds.right,bounds.bottom).all(Float::isFinite)) {
            clear(); return
        }
        if(this.bounds==bounds && view!=null) return
        clear() // A new position never interpolates through unrelated controls.
        this.bounds=bounds
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE; strokeWidth=3*service.resources.displayMetrics.density
            color=if(service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
                0xFFC7B4F7.toInt() else 0xFF7057A3.toInt()
        }
        val target=object: View(service) {
            override fun onDraw(canvas: Canvas) {
                val location=IntArray(2); getLocationOnScreen(location)
                val inset=paint.strokeWidth/2
                canvas.drawRoundRect(bounds.left-location[0]+inset,bounds.top-location[1]+inset,
                    bounds.right-location[0]-inset,bounds.bottom-location[1]-inset,8f,8f,paint)
            }
        }.apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable=false }
        val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="Nanodot Care focus"
        }
        windows.addView(target,p); view=target; changed(target)
        // ValueAnimator uses the platform duration scale. At zero there is no animator at all.
        if(Settings.Global.getFloat(service.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f) {
            animation=ValueAnimator.ofFloat(0f,1f).apply {
                duration=DotTokens.Motion.Focus.toLong()
                addUpdateListener { paint.alpha=((it.animatedValue as Float)*255).toInt(); target.invalidate() }; start()
            }
        }
    }
    fun clear() {
        animation?.cancel(); animation=null
        view?.let { windows.removeViewImmediate(it) }; view=null; bounds=null; changed(null)
    }
}
