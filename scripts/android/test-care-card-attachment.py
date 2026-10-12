#!/usr/bin/env python3
"""Execute the real care window against controlled attach/measure SDK boundaries.

This is an offline scheduling regression, not a Compose or native Android test.
It uses already installed Kotlin compiler/runtime jars; no dependencies are fetched.
Native Compose rendering and device behavior still require emulator/device QA.
"""

import argparse
import os
import pathlib
import subprocess
import tempfile

STUBS = {
    "content.kt": r"""
package android.content
class ClipData { companion object { fun newPlainText(label: String, text: String) = ClipData() } }
class ClipboardManager { fun setPrimaryClip(data: ClipData) {} }
""",
    "graphics.kt": "package android.graphics\nobject PixelFormat { const val TRANSLUCENT = -3 }\n",
    "build.kt": "package android.os\nobject Build { object VERSION { var SDK_INT = 35 } }\n",
    "service.kt": r"""
package android.accessibilityservice
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityWindowInfo
class Metrics { val density = 1f }
class Resources { val displayMetrics = Metrics() }
open class AccessibilityService(val manager: WindowManager) {
    val resources = Resources()
    var imeShown = false
    val observedWindows = mutableListOf<AccessibilityWindowInfo>()
    val windows get() = (if(imeShown) listOf(AccessibilityWindowInfo(AccessibilityWindowInfo.TYPE_INPUT_METHOD)) else emptyList()).also { observedWindows.addAll(it) }
    val ime = InputMethodManager { imeShown = false }
    fun <T> getSystemService(type: Class<T>): T = type.cast(if(type == InputMethodManager::class.java) ime else manager)
    fun getString(id: Int) = "Copy"
}
""",
    "view.kt": r"""
package android.view
import android.accessibilityservice.AccessibilityService
import android.window.OnBackInvokedDispatcher
object Gravity { const val TOP = 48; const val LEFT = 3 }
class KeyEvent(val keyCode: Int, val action: Int) {
    companion object { const val KEYCODE_BACK = 4; const val ACTION_DOWN = 0; const val ACTION_UP = 1 }
}
class WindowInsets
open class View(val service: AccessibilityService) {
    var isAttachedToWindow = false
    var measuredHeight = 0
    var measuredWidth = 0
    var measures = 0
    val windowToken = Any()
    val backDispatcher = OnBackInvokedDispatcher()
    val posts = ArrayDeque<Runnable>()
    val attachListeners = mutableListOf<OnAttachStateChangeListener>()
    var layoutListener: (() -> Unit)? = null
    var insetsListener: ((View, WindowInsets) -> WindowInsets)? = null
    fun post(action: Runnable): Boolean { posts.add(action); return true }
    fun removeCallbacks(action: Runnable): Boolean = posts.removeAll { it === action }
    fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.add(listener) }
    fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.remove(listener) }
    fun addOnLayoutChangeListener(listener: (View, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit) {
        layoutListener = { listener(this, 0, 0, 0, 0, 0, 0, 0, 0) }
    }
    fun setOnApplyWindowInsetsListener(listener: (View, WindowInsets) -> WindowInsets) { insetsListener = listener }
    fun findOnBackInvokedDispatcher(): OnBackInvokedDispatcher? = backDispatcher
    fun getLocationOnScreen(location: IntArray) { location[0] = 0; location[1] = 0 }
    open fun dispatchKeyEventPreIme(event: KeyEvent) = false
    open fun dispatchKeyEvent(event: KeyEvent) = false
    fun measure(width: Int, height: Int) { measures++; onMeasure(width, height) }
    open fun onMeasure(width: Int, height: Int) {
        measuredWidth = MeasureSpec.getSize(width); measuredHeight = minOf(160, MeasureSpec.getSize(height))
    }
    open fun attach() {
        isAttachedToWindow = true
        attachListeners.toList().forEach { it.onViewAttachedToWindow(this) }
    }
    open fun detach() {
        isAttachedToWindow = false
        attachListeners.toList().forEach { it.onViewDetachedFromWindow(this) }
    }
    interface OnAttachStateChangeListener {
        fun onViewAttachedToWindow(view: View)
        fun onViewDetachedFromWindow(view: View)
    }
    object MeasureSpec {
        const val EXACTLY = 1 shl 30
        const val AT_MOST = 2 shl 30
        fun makeMeasureSpec(size: Int, mode: Int) = size or mode
        fun getSize(spec: Int) = spec and 0x3fffffff
    }
}
open class WindowManager {
    val added = mutableListOf<View>()
    val attempted = mutableListOf<View>()
    val live = mutableSetOf<View>()
    val updates = mutableListOf<List<Int>>()
    var addFails = false
    var emitLayoutOnUpdate = false
    open fun addView(view: View, params: LayoutParams) {
        attempted.add(view)
        if (addFails) throw IllegalStateException("test add failure")
        added.add(view); live.add(view) // addView returns before native attachment.
    }
    fun updateViewLayout(view: View, params: LayoutParams) {
        check(view in live && view.isAttachedToWindow)
        val geometry = listOf(params.width, params.height, params.x, params.y)
        val changed = updates.lastOrNull() != geometry
        updates.add(geometry)
        if (emitLayoutOnUpdate && changed) view.layoutListener?.invoke()
    }
    fun removeViewImmediate(view: View) { check(live.remove(view)); view.detach() }
    class LayoutParams(var width: Int, var height: Int, val type: Int, val flags: Int, val format: Int) {
        var gravity = 0; var title = ""; var softInputMode = 0; var x = 0; var y = 0
        companion object {
            const val TYPE_ACCESSIBILITY_OVERLAY = 2032
            const val FLAG_LAYOUT_IN_SCREEN = 256
            const val FLAG_NOT_TOUCH_MODAL = 32
            const val SOFT_INPUT_ADJUST_RESIZE = 16
        }
    }
}
""",
    "frame.kt": r"""
package android.widget
import android.accessibilityservice.AccessibilityService
import android.view.View
open class FrameLayout(service: AccessibilityService): View(service) {
    val children = mutableListOf<View>()
    fun addView(child: View, params: LayoutParams) { children.add(child) }
    class LayoutParams(val width: Int, val height: Int)
    override fun onMeasure(width: Int, height: Int) {
        children.forEach { it.measure(width, height) }
        super.onMeasure(width, height)
    }
    override fun attach() { children.forEach { it.attach() }; super.attach() }
    override fun detach() { children.forEach { it.detach() }; super.detach() }
}
""",
    "compose.kt": r"""
package androidx.compose.ui.platform
import android.accessibilityservice.AccessibilityService
import android.view.View
class ComposeView(service: AccessibilityService): View(service) {
    var disposed = false
    fun setViewCompositionStrategy(strategy: Any) {}
    fun setContent(content: () -> Unit) {}
    fun disposeComposition() { disposed = true }
    override fun onMeasure(width: Int, height: Int) {
        check(isAttachedToWindow) { "Cannot locate windowRecomposer; ComposeView is not attached to a window" }
        super.onMeasure(width, height)
    }
}
object ViewCompositionStrategy { val DisposeOnViewTreeLifecycleDestroyed = Any() }
""",
    "lifecycle.kt": r"""
package androidx.lifecycle
import android.view.View
import io.github.nanomuse.dot.overlay.DotOverlayOwner
fun View.setViewTreeLifecycleOwner(owner: DotOverlayOwner) {}
""",
    "saved.kt": r"""
package androidx.savedstate
import android.view.View
import io.github.nanomuse.dot.overlay.DotOverlayOwner
fun View.setViewTreeSavedStateRegistryOwner(owner: DotOverlayOwner) {}
""",
    "back.kt": r"""
package android.window
fun interface OnBackInvokedCallback { fun onBackInvoked() }
class OnBackInvokedDispatcher {
    var registered: OnBackInvokedCallback? = null
    fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) { registered = callback }
    fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) { check(registered === callback); registered = null }
    companion object { const val PRIORITY_DEFAULT = 0 }
}
""",
    "ime.kt": r"""
package android.view.inputmethod
class InputMethodManager(private val hidden: () -> Unit) {
    var hides = 0
    fun hideSoftInputFromWindow(token: Any?, flags: Int): Boolean { hides++; hidden(); return true }
}
""",
    "ax-window.kt": r"""
package android.view.accessibility
class AccessibilityWindowInfo(val type: Int) {
    var recycled = false
    fun recycle() { recycled = true }
    companion object { const val TYPE_INPUT_METHOD = 2 }
}
""",
    "r.kt": "package com.openminis.app\nobject R { object string { const val nm_care_copy = 1 } }\n",
    "minis.kt": r"""
package com.openminis.app.accessibility
import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.WindowManager
class Registry {
    val registered = mutableSetOf<View>()
    var active = false
    fun registerCareWindow(view: View) { registered.add(view) }
    fun unregisterCareWindow(view: View) { registered.remove(view) }
    fun setCareActive(value: Boolean) { active = value }
}
class MinisAccessibilityService(manager: WindowManager): AccessibilityService(manager) { val dotOverlay = Registry() }
""",
    "speech.kt": r"""
package com.openminis.app.speech
import android.accessibilityservice.AccessibilityService
class ReadAloudPlayer(service: AccessibilityService) {
    fun speak(instruction: String) {}; fun stop() {}; fun shutdown() {}
}
""",
    "theme.kt": "package com.openminis.app.ui.theme\nfun MinisTheme(content: () -> Unit) {}\n",
    "motion.kt": "package io.github.nanomuse.dot.ui\nfun DotMotionHost(content: () -> Unit) {}\n",
    "owner.kt": r"""
package io.github.nanomuse.dot.overlay
class DotOverlayOwner {
    var destroyed = false
    fun start() {}; fun destroy() { destroyed = true }
}
""",
    "screen.kt": r"""
package io.github.nanomuse.dot.screen
data class RectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left; val height get() = bottom - top
}
class SceneKey
""",
    "care.kt": r"""
package io.github.nanomuse.dot.care
import android.accessibilityservice.AccessibilityService
import android.view.View
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.flow.MutableStateFlow
class Proposal(val copyText: String)
class State(val proposal: Proposal? = null)
class Controller { val state = MutableStateFlow(State()) }
class CareSession {
    val controller = Controller()
    val visible = MutableStateFlow(false)
    val editingGoal = MutableStateFlow(false)
    var target: RectPx? = null
    fun focusBounds(scene: SceneKey?) = target
    fun observeConversation() {}
    fun instructionKey() = "current"
    fun pause(hide: Boolean) { if (hide) visible.value = false }
}
class CareFocusLayer(service: AccessibilityService, owner: (View?) -> Unit) {
    fun clear() {}; fun show(target: RectPx, key: String = "") {}
    fun setInstructionBubble(target: RectPx?) {}
}
fun CarePanel(session: CareSession, copy: () -> Unit, onRepeat: (String) -> Unit,
    onInstructionBounds: (String?, RectPx?) -> Unit = { _, _ -> }) {}
""",
    "harness.kt": r"""
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.care.*
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.*

class Rig {
    val manager = WindowManager()
    val service = MinisAccessibilityService(manager)
    val errors = mutableListOf<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> errors.add(error) })
    val session = CareSession()
    var host: RectPx? = RectPx(0f, 24f, 400f, 824f)
    val window = CareOverlayWindow(service, scope, session, { host }, { null })
    fun open(): View { session.visible.value = true; return manager.added.last() }
    fun pump(view: View) { val pending = view.posts.size; repeat(pending) { view.posts.removeFirst().run() } }
    fun close() {
        window.close(); scope.cancel()
        check(errors.isEmpty()) { "Uncaught care render failure: ${errors.first()}" }
    }
}

fun main() {
    val failures = mutableListOf<String>()
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        try { body(); println("PASS $name"); passed++ }
        catch (error: Throwable) { println("FAIL $name: $error"); failures.add(name) }
    }
    test("opening care never measures a detached Compose card") {
        val r = Rig()
        try {
            val view = r.open()
            check(view.measures == 0) { "Card measured before native attachment" }
            check(r.manager.updates.isEmpty())
            view.attach(); r.pump(view)
            check(view.measures > 0) { "Card was not sized after attachment" }
            check(r.manager.updates.last() == listOf(360, 160, 20, 656))
        } finally { r.close() }
    }
    test("attached refresh uses the current IME viewport and target-free side") {
        val r = Rig()
        try {
            val view = r.open(); view.attach(); r.pump(view)
            r.host = RectPx(100f, 24f, 700f, 224f)
            r.window.refreshGeometry()
            check(r.manager.updates.last() == listOf(360, 156, 220, 60))
            r.host = RectPx(0f, 24f, 400f, 824f)
            r.session.target = RectPx(0f, 700f, 400f, 800f)
            r.window.refreshGeometry()
            check(r.manager.updates.last() == listOf(360, 160, 20, 532))
        } finally { r.close() }
    }
    test("closing before attach prevents queued layout from reviving the card") {
        val r = Rig()
        try {
            val old = r.open()
            r.session.visible.value = false
            val count = r.manager.updates.size
            old.attach(); r.pump(old)
            check(old.measures == 0)
            check(r.manager.updates.size == count && r.manager.live.isEmpty())
            check(r.service.dotOverlay.registered.isEmpty() && !r.service.dotOverlay.active)
            check(old.attachListeners.isEmpty() && old.posts.isEmpty())
            check((old as FrameLayout).children.filterIsInstance<ComposeView>().single().disposed)
        } finally { r.close() }
    }
    test("stale callbacks cannot measure an old card or replace current ownership") {
        val r = Rig()
        try {
            val old = r.open(); old.attach(); r.pump(old)
            old.layoutListener!!.invoke()
            r.session.visible.value = false
            val fresh = r.open()
            val count = old.measures
            r.pump(old)
            check(old.measures == count && fresh.measures == 0)
            fresh.attach(); r.pump(fresh)
            check(fresh.measures > 0)
            check(r.service.dotOverlay.registered == setOf(fresh))
        } finally { r.close() }
    }
    test("close cancels queued work and removes registered ownership") {
        val r = Rig()
        val view = r.open(); view.attach(); r.pump(view)
        view.layoutListener!!.invoke()
        val count = view.measures
        r.close(); r.pump(view)
        check(view.measures == count && r.manager.live.isEmpty())
        check(r.service.dotOverlay.registered.isEmpty() && !r.service.dotOverlay.active)
        check(view.attachListeners.isEmpty() && view.posts.isEmpty())
    }
    test("temporary detach cancels queued measurement and reattach resizes from fresh host") {
        val r = Rig()
        try {
            val view = r.open(); view.attach(); r.pump(view)
            view.layoutListener!!.invoke(); view.detach()
            val count = view.measures
            r.host = RectPx(100f, 24f, 700f, 224f)
            r.window.refreshGeometry(); r.pump(view)
            check(view.measures == count && view.posts.isEmpty())
            view.attach(); r.pump(view)
            check(r.manager.updates.last() == listOf(360, 156, 220, 60))
        } finally { r.close() }
    }
    test("layout changes queue rather than recursively remeasure and settle at unchanged bounds") {
        val r = Rig()
        try {
            r.manager.emitLayoutOnUpdate = true
            val view = r.open(); view.attach(); r.pump(view)
            check(view.measures == 1 && r.manager.updates.size == 1)
            r.pump(view)
            check(view.measures == 2 && view.posts.isEmpty()) {
                "Settled card retained callbacks: measures=${view.measures}, posts=${view.posts.size}"
            }
        } finally { r.close() }
    }
    test("add failure disposes Compose and cancels attachment callbacks before rethrow") {
        val r = Rig()
        try {
            r.manager.addFails = true
            r.session.visible.value = true
            check(r.errors.single().message == "test add failure")
            r.errors.clear() // The failure was intentionally observed, not swallowed by production.
            val failed = r.manager.attempted.single() as FrameLayout
            check(failed.children.filterIsInstance<ComposeView>().single().disposed)
            check(failed.attachListeners.isEmpty() && failed.posts.isEmpty())
            failed.attach(); r.pump(failed)
            check(failed.measures == 0 && r.manager.live.isEmpty())
            check(r.service.dotOverlay.registered.isEmpty())
        } finally { r.close() }
    }
    test("legacy direct preIME Back hides keyboard and preserves card before next Back closes") {
        android.os.Build.VERSION.SDK_INT = 26
        val r = Rig()
        try {
            val view = r.open(); view.attach(); r.pump(view)
            val compose = (view as FrameLayout).children.filterIsInstance<ComposeView>().single()
            r.service.imeShown = true
            check(view.dispatchKeyEventPreIme(android.view.KeyEvent(4,0)))
            check(view.dispatchKeyEventPreIme(android.view.KeyEvent(4,1)))
            check(r.service.ime.hides == 1) { "First Back did not request keyboard hiding" }
            check(r.session.visible.value && view in r.manager.live && !compose.disposed) { "First Back destroyed the draft composition" }
            check(r.service.observedWindows.all { it.recycled })
            view.dispatchKeyEventPreIme(android.view.KeyEvent(4,0)); view.dispatchKeyEventPreIme(android.view.KeyEvent(4,1))
            check(!r.session.visible.value && r.manager.live.isEmpty() && compose.disposed)
        } finally { r.close(); android.os.Build.VERSION.SDK_INT = 35 }
    }
    test("API33 direct Back callback is keyboard-first, unregisters, and stale callback cannot close fresh card") {
        val r = Rig()
        try {
            val view = r.open(); view.attach(); r.pump(view)
            val callback = requireNotNull(view.backDispatcher.registered)
            r.service.imeShown = true; callback.onBackInvoked()
            check(r.service.ime.hides == 1 && r.session.visible.value && view in r.manager.live)
            callback.onBackInvoked()
            check(!r.session.visible.value && view.backDispatcher.registered == null && r.manager.live.isEmpty())
            val fresh = r.open(); fresh.attach(); r.pump(fresh)
            callback.onBackInvoked()
            check(r.session.visible.value && fresh in r.manager.live) { "Stale Back callback closed a different card" }
        } finally { r.close() }
    }
    println("RESULT passed=$passed failed=${failures.size}")
    check(failures.isEmpty()) { failures.joinToString() }
}
""",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=pathlib.Path)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--runtime-classpath", required=True)
    parser.add_argument(
        "--source-root",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[2],
    )
    args = parser.parse_args()
    source_dir = (
        args.source_root / "android/src/android/app/src/main/java/io/github/nanomuse/dot/care"
    )
    with tempfile.TemporaryDirectory(prefix="care-card-attachment-") as temp:
        folder = pathlib.Path(temp)
        for name, content in STUBS.items():
            (folder / name).write_text(content, encoding="utf-8")
        output = folder / "classes"
        sources = sorted(folder.glob("*.kt")) + [
            source_dir / "CareOverlayWindow.kt",
            source_dir / "CareCardGeometry.kt",
            source_dir.parent / "overlay/DotForegroundGeometry.kt",
        ]
        compile_result = subprocess.run(
            [
                str(args.java),
                "-cp",
                args.compiler_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target",
                "17",
                "-nowarn",
                "-classpath",
                args.runtime_classpath,
                "-d",
                str(output),
                *map(str, sources),
            ],
            check=False,
            timeout=180,
        )
        if compile_result.returncode:
            return compile_result.returncode
        return subprocess.run(
            [str(args.java), "-cp", str(output) + os.pathsep + args.runtime_classpath, "HarnessKt"],
            check=False,
            timeout=30,
        ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
