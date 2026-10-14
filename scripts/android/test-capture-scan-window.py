#!/usr/bin/env python3
"""Run the real scan window/view against controlled native scheduling boundaries.

Offline Kotlin host regression; native Canvas pixels, traversal and window placement
still require the separate Android instrumentation and real double-capture gate.
"""

import argparse
import os
import pathlib
import subprocess
import tempfile

STUBS = {
    "context.kt": """package android.content
open class Context {
 val contentResolver=Any(); val resources=Resources(); val manager=android.view.WindowManager()
 fun <T> getSystemService(type: Class<T>):T=type.cast(manager)
}
class Resources { val displayMetrics=Metrics() }; class Metrics { val density=1f }
""",
    "service.kt": "package android.accessibilityservice\nopen class AccessibilityService:android.content.Context()\n",
    "settings.kt": """package android.provider
object Settings { object Global {
 const val ANIMATOR_DURATION_SCALE="animator_duration_scale"; var stored:Float?=null
 fun getFloat(resolver:Any,key:String,fallback:Float)=stored ?: fallback
} }
""",
    "clock.kt": """package android.os
object Build { object VERSION { var SDK_INT=35 } }
class Looper { companion object { private val main=Looper();fun getMainLooper()=main;fun myLooper()=main } }
object TestClock {
 var now=0L; val actions=mutableListOf<Pair<Long,Runnable>>()
 fun reset(){now=0;actions.clear()}
 fun advance(ms:Long){now+=ms;val due=actions.filter{it.first<=now};actions.removeAll(due.toSet());due.forEach{it.second.run()}}
}
class Handler(looper:Looper) {
 fun postDelayed(r:Runnable,ms:Long):Boolean { TestClock.actions.add(TestClock.now+ms to r);return true }
 fun removeCallbacks(r:Runnable){TestClock.actions.removeAll{it.second===r}}
}
""",
    "animator.kt": """package android.animation
open class Animator
open class AnimatorListenerAdapter { open fun onAnimationEnd(animation:Animator){} }
class ValueAnimator:Animator(){
 var duration=300L;var animatedValue:Any=0f;var interpolator:Any?=null;var paused=false;var cancelled=false
 private val updates=mutableListOf<(ValueAnimator)->Unit>();private val listeners=mutableListOf<AnimatorListenerAdapter>()
 fun addUpdateListener(l:(ValueAnimator)->Unit){updates.add(l)}
 fun addListener(l:AnimatorListenerAdapter){listeners.add(l)}
 fun removeAllListeners(){listeners.clear()}
 fun removeAllUpdateListeners(){updates.clear()}
 fun cancel(){cancelled=true;listeners.toList().forEach{it.onAnimationEnd(this)}}
 fun pause(){paused=true};fun resume(){paused=false}
 fun start(){started.add(this)}
 fun frame(fraction:Float){if(cancelled||paused)return;animatedValue=if(scale==0f)1f else fraction;updates.toList().forEach{it(this)};if(animatedValue==1f)listeners.toList().forEach{it.onAnimationEnd(this)}}
 companion object {
  var scale=1f;val started=mutableListOf<ValueAnimator>()
  fun ofFloat(a:Float,b:Float)=ValueAnimator();fun areAnimatorsEnabled()=scale!=0f
 }
}
""",
    "interpolator.kt": "package android.view.animation\nclass LinearInterpolator\n",
    "view.kt": """package android.view
import android.content.Context
import android.graphics.Canvas
object Gravity { const val TOP=48;const val LEFT=3 }
class WindowInsets { object Type { fun systemBars()=7 } }
open class View(val context:Context){
 val resources get()=context.resources
 var width=0;var height=0;var alpha=1f;var visibility=VISIBLE
 var windowVisibility=VISIBLE;var isAttachedToWindow=false;val isShown get()=isAttachedToWindow&&visibility==VISIBLE
 var importantForAccessibility=0;var isFocusable=false
 val frames=mutableListOf<Runnable>();val attachListeners=mutableListOf<OnAttachStateChangeListener>()
 fun invalidate(){}
 fun postOnAnimation(r:Runnable){frames.add(r)}
 fun removeCallbacks(r:Runnable):Boolean=frames.removeAll{it===r}
 fun addOnAttachStateChangeListener(l:OnAttachStateChangeListener){attachListeners.add(l)}
 fun removeOnAttachStateChangeListener(l:OnAttachStateChangeListener){attachListeners.remove(l)}
 fun attach(w:Int=1080,h:Int=2400){width=w;height=h;isAttachedToWindow=true;attachListeners.toList().forEach{it.onViewAttachedToWindow(this)}}
 fun detach(){isAttachedToWindow=false;onDetachedFromWindow();attachListeners.toList().forEach{it.onViewDetachedFromWindow(this)}}
 fun draw(){onDraw(Canvas())};fun nextFrame(){val pending=frames.toList();frames.clear();pending.forEach{it.run()}}
 protected open fun onDraw(c:Canvas){};protected open fun onDetachedFromWindow(){}
 interface OnAttachStateChangeListener {fun onViewAttachedToWindow(v:View);fun onViewDetachedFromWindow(v:View)}
 companion object {const val IMPORTANT_FOR_ACCESSIBILITY_NO=2;const val VISIBLE=0}
}
class WindowManager {
 val live=mutableSetOf<View>();var params:LayoutParams?=null
 fun addView(v:View,p:LayoutParams){live.add(v);params=p}
 fun removeViewImmediate(v:View){check(live.remove(v));v.detach()}
 class LayoutParams(val width:Int,val height:Int,val type:Int,val flags:Int,val format:Int){
  var gravity=0;var title="";var layoutInDisplayCutoutMode=0;private var fits=7;val fitInsetsTypes get()=fits
  fun setFitInsetsTypes(v:Int){fits=v}
  companion object {
   const val TYPE_ACCESSIBILITY_OVERLAY=2032;const val FLAG_NOT_TOUCHABLE=16;const val FLAG_NOT_FOCUSABLE=8
   const val FLAG_LAYOUT_IN_SCREEN=256;const val FLAG_LAYOUT_NO_LIMITS=512;const val FLAG_LAYOUT_INSET_DECOR=65536
   const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES=1;const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS=3
  }
 }
}
""",
    "graphics.kt": """package android.graphics
object PixelFormat {const val TRANSLUCENT=-3}
class Paint(flags:Int){var color=0;var style=Style.STROKE;var strokeWidth=0f;var alpha=255;enum class Style{STROKE};companion object{const val ANTI_ALIAS_FLAG=1}}
class Canvas {fun drawPath(p:Path,paint:Paint){}}
class Path {enum class Direction{CW};fun addRoundRect(l:Float,t:Float,r:Float,b:Float,rx:Float,ry:Float,d:Direction){}}
class PathMeasure(p:Path,closed:Boolean){val length=100f;fun getSegment(a:Float,b:Float,p:Path,start:Boolean)=b>a}
""",
    "color.kt": """package androidx.compose.ui.graphics
class Color(val argb:Int);fun Color.toArgb()=argb
""",
    "tokens.kt": """package io.github.nanomuse.dot
object DotColors {val LightPrimary=androidx.compose.ui.graphics.Color(0xff2e679e.toInt())}
""",
    "harness.kt": """package io.github.nanomuse.dot.overlay
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.os.TestClock
import android.provider.Settings
import android.view.WindowManager

fun main(){
 var passed=0;var failed=0
 fun test(name:String,body:()->Unit){
  TestClock.reset();ValueAnimator.started.clear();ValueAnimator.scale=1f;Settings.Global.stored=null
  try{body();println("PASS $name");passed++}catch(t:Throwable){println("FAIL $name: ${t.message}");failed++}
 }
 fun visible(v:DotCaptureScanner){v.attach();v.draw();v.nextFrame()}
 test("runtime zero never enters animated success despite missing setting"){
  ValueAnimator.scale=0f;val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);o.finish(1,true)
  val v=checkNotNull(o.view);visible(v)
  check(ValueAnimator.started.isEmpty()){ "runtime0 started animator" };check(v.progress==1f)
  TestClock.advance(249);check(o.view===v);TestClock.advance(1);check(o.view==null)
 }
 test("explicit zero setting is static even if runtime animation is enabled"){
  Settings.Global.stored=0f;val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);o.finish(1,true);visible(checkNotNull(o.view))
  check(ValueAnimator.started.isEmpty());TestClock.advance(250);check(o.view==null)
 }
 test("motion and lifetime wait for positive attached visible draw"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);o.finish(1,true);val v=checkNotNull(o.view)
  TestClock.advance(2000);check(ValueAnimator.started.isEmpty()){ "animation started before attachment/draw" };check(o.view===v)
  v.attach(0,0);v.draw();v.nextFrame();check(ValueAnimator.started.isEmpty())
  v.attach();v.alpha=0f;v.draw();v.nextFrame();TestClock.advance(2000);check(ValueAnimator.started.isEmpty());check(o.view===v)
  v.alpha=1f;v.nextFrame();check(ValueAnimator.started.isEmpty()){ "registration/frame is not visible drawing" }
  v.draw();v.nextFrame();check(ValueAnimator.started.size==1);check(ValueAnimator.started.single().duration==720L)
  ValueAnimator.started.single().frame(1f);TestClock.advance(99);check(o.view===v);TestClock.advance(1);check(o.view==null)
 }
 test("capture invalidates ready callback then requires a fresh restored draw"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);val v=checkNotNull(o.view);v.attach();v.draw()
  val resume=o.pauseForCapture();v.alpha=0f;v.nextFrame();check(ValueAnimator.started.isEmpty())
  v.alpha=1f;resume();v.nextFrame();check(ValueAnimator.started.isEmpty()){ "old pre-capture draw started animation" }
  v.draw();v.nextFrame();check(ValueAnimator.started.size==1)
 }
 test("failure remains mounted until its static outcome was visibly drawn"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);o.finish(1,false);val v=checkNotNull(o.view)
  v.attach();v.alpha=0f;v.draw();v.nextFrame();TestClock.advance(1000);check(o.view===v){ "hidden failure expired" }
  v.alpha=1f;v.draw();v.nextFrame();check(v.progress==1f);check(ValueAnimator.started.isEmpty())
  TestClock.advance(249);check(o.view===v);TestClock.advance(1);check(o.view==null)
 }
 test("cancel removes readiness and cannot start or expire next ticket"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);val old=checkNotNull(o.view);old.attach();old.draw();val resume=o.pauseForCapture()
  o.cancel();o.begin(2);val next=checkNotNull(o.view);resume();old.nextFrame();check(ValueAnimator.started.isEmpty());check(o.view===next)
  o.finish(1,false);check(next.outcome==null);o.cancel(1);check(o.view===next)
  visible(next);check(ValueAnimator.started.size==1);o.cancel();TestClock.advance(2000);check(o.view==null)
 }
 test("native detach clears callbacks animation and outcome ownership"){
  val service=AccessibilityService();val o=DotCaptureScanWindow(service);o.begin(1);val v=checkNotNull(o.view);visible(v)
  service.manager.live.remove(v);v.detach();check(o.view==null){ "detached scanner retained" };check(ValueAnimator.started.single().cancelled)
  v.nextFrame();o.finish(1,true);TestClock.advance(2000);check(o.view==null)
 }
 test("completed motion interrupted by capture rearms visible outcome lifetime"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);o.finish(1,true);val v=checkNotNull(o.view);visible(v)
  ValueAnimator.started.single().frame(1f);val resume=o.pauseForCapture();v.alpha=0f;TestClock.advance(1000);check(o.view===v)
  v.alpha=1f;resume();v.draw();v.nextFrame();check(ValueAnimator.started.size==2){ "completed animation retained instead of rearming outcome" }
  ValueAnimator.started.last().frame(1f);TestClock.advance(100);check(o.view==null)
 }
 test("old readiness cancellation cannot discard a replacement draw callback"){
  val v=DotCaptureScanner(AccessibilityService());v.attach();val stale=v.afterVisibleDraw{};v.draw()
  var callbacks=0;v.afterVisibleDraw{callbacks++};v.draw();stale();v.nextFrame();check(callbacks==1){"stale cancellation removed replacement callback"}
 }
 test("nonvisible native window cannot establish readiness"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);val v=checkNotNull(o.view);v.attach();v.windowVisibility=8;v.draw();v.nextFrame()
  check(ValueAnimator.started.isEmpty()){ "window-hidden draw started animation" };v.windowVisibility=0;v.draw();v.nextFrame();check(ValueAnimator.started.size==1);o.cancel()
 }
 test("finishing window cannot be orphaned by direct begin before cleanup"){
  val service=AccessibilityService();val o=DotCaptureScanWindow(service);o.begin(1);o.finish(1,true);val old=checkNotNull(o.view);o.begin(2)
  check(service.manager.live.size==1){ "begin orphaned the old owned surface" };check(o.view===old);o.cancel()
 }
 test("runtime motion disabled during capture switches paused animation to static"){
  val o=DotCaptureScanWindow(AccessibilityService());o.begin(1);val v=checkNotNull(o.view);visible(v);val old=ValueAnimator.started.single()
  val resume=o.pauseForCapture();v.alpha=0f;ValueAnimator.scale=0f;v.alpha=1f;resume();v.draw();v.nextFrame()
  check(old.cancelled&&v.progress==1f){ "restored scan resumed an effectively disabled animator" };o.finish(1,true);v.draw();v.nextFrame()
  TestClock.advance(249);check(o.view===v);TestClock.advance(1);check(o.view==null)
 }
 test("display window disables inset fitting and never intercepts or focuses"){
  val service=AccessibilityService();val o=DotCaptureScanWindow(service);o.begin(1);val p=checkNotNull(service.manager.params)
  check(p.fitInsetsTypes==0){ "display scanner still fits system insets" };check(p.layoutInDisplayCutoutMode==3)
  check(p.width==-1&&p.height==-1);check(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
  check(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE!=0);o.cancel()
 }
 println("SCAN_WINDOW_HOST $passed passed, $failed failed");check(failed==0){"$failed scanner regressions"}
}
""",
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--java", required=True)
    p.add_argument("--compiler-classpath", required=True)
    p.add_argument("--runtime-classpath", required=True)
    p.add_argument(
        "--source-root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2]
    )
    a = p.parse_args()
    src = a.source_root / "android/src/android/app/src/main/java/io/github/nanomuse/dot/overlay"
    with tempfile.TemporaryDirectory(prefix="dot-capture-scan-") as temp:
        d = pathlib.Path(temp)
        for name, text in STUBS.items():
            (d / name).write_text(text)
        owner = src / "DotCaptureScanWindow.kt"
        sources = [
            *sorted(d.glob("*.kt")),
            src / "DotCaptureScanner.kt",
            src / "DotCaptureScanState.kt",
        ]
        sources.append(owner)
        output = d / "classes"
        c = subprocess.run(
            [
                a.java,
                "-cp",
                a.compiler_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib",
                "-no-reflect",
                "-nowarn",
                "-jvm-target",
                "17",
                "-classpath",
                a.runtime_classpath,
                "-d",
                str(output),
                *map(str, sources),
            ]
        )
        if c.returncode:
            return c.returncode
        return subprocess.run(
            [
                a.java,
                "-cp",
                str(output) + os.pathsep + a.runtime_classpath,
                "io.github.nanomuse.dot.overlay.HarnessKt",
            ]
        ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
