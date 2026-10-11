package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.ui.graphics.toArgb
import io.github.nanomuse.dot.DotColors
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
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
    private var instruction: RectPx?=null
    private var identity: String?=null
    private var progress=1f
    fun setInstructionBubble(bounds: RectPx?) {
        instruction=bounds?.takeIf { it.width>0 && it.height>0 &&
            listOf(it.left,it.top,it.right,it.bottom).all(Float::isFinite) }
        if (instruction != null && progress == 0f && animation == null) startTrace()
        view?.invalidate()
    }
    fun show(bounds: RectPx, key: String = "") {
        if(bounds.width<=0 || bounds.height<=0 || !listOf(bounds.left,bounds.top,bounds.right,bounds.bottom).all(Float::isFinite)) {
            clear(); return
        }
        if(this.bounds==bounds && identity==key && view!=null) return
        val bubble=instruction
        clear() // A new position never interpolates through unrelated controls.
        this.bounds=bounds; identity=key; instruction=bubble
        progress = if (motionEnabled()) 0f else 1f
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE; strokeWidth=3*service.resources.displayMetrics.density
            color=if(service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
                DotColors.DarkCare.toArgb() else DotColors.LightCare.toArgb()
        }
        val target=object: View(service) {
            override fun onDraw(canvas: Canvas) {
                val location=IntArray(2); getLocationOnScreen(location)
                val inset=paint.strokeWidth/2
                val left=bounds.left-location[0]+inset; val top=bounds.top-location[1]+inset
                val right=bounds.right-location[0]-inset; val bottom=bounds.bottom-location[1]-inset
                val outline=Path().apply { addRoundRect(left,top,right,bottom,8f,8f,Path.Direction.CW) }
                val cue=careCueProgress((progress*980).toLong(),
                    Settings.Global.getFloat(service.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f)
                val measure=PathMeasure(outline,false)
                val traced=Path()
                measure.getSegment(0f,measure.length*cue.outline,traced,true)
                canvas.drawPath(traced,paint)
                instruction?.let { bubble ->
                    val endX=((bubble.left+bubble.right)/2)-location[0]
                    val above=bubble.bottom<=bounds.top
                    val startX=((bounds.left+bounds.right)/2)-location[0]
                    val startY=(if(above) bounds.top else bounds.bottom)-location[1]
                    val endY=(if(above) bubble.bottom else bubble.top)-location[1]
                    val bend=(startY+endY)/2
                    val line=Path().apply { moveTo(startX,startY); lineTo(startX,bend); lineTo(endX,bend); lineTo(endX,endY) }
                    val leader=PathMeasure(line,false); val segment=Path()
                    leader.getSegment(0f,leader.length*cue.leader,segment,true)
                    canvas.drawPath(segment,paint)
                    if(cue.leader>=1f) canvas.drawCircle(endX,endY,paint.strokeWidth,paint)
                }
            }
        }.apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable=false }
        val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="Nanodot Care focus"
        }
        windows.addView(target,p); view=target; changed(target)
        if (instruction != null) startTrace()
    }
    private fun motionEnabled() = Settings.Global.getFloat(service.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    /** Start both visual stages only after the current instruction has a measured anchor. */
    private fun startTrace() {
        val target=view ?: return
        if (!motionEnabled()) { progress=1f; target.invalidate(); return }
        animation=ValueAnimator.ofFloat(0f,1f).apply {
            duration=980L
            interpolator=LinearInterpolator()
            addUpdateListener { progress=it.animatedValue as Float; target.invalidate() }; start()
        }
    }
    fun clear() {
        animation?.cancel(); animation=null
        view?.let { windows.removeViewImmediate(it) }; view=null; bounds=null; identity=null; instruction=null; progress=1f; changed(null)
    }
}
