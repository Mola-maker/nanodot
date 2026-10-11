package io.github.nanomuse.dot.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.goals.Goal
import io.github.nanomuse.goals.GoalStatus
import io.github.nanomuse.goals.GoalStore
import io.github.nanomuse.feed.FeedStore
import io.github.nanomuse.feed.FeedPost
import io.github.nanomuse.ui.goals.GoalsTab
import io.github.nanomuse.ui.feed.FeedTab
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Focused review regressions: actual OS input, pixels and production store callbacks. */
@RunWith(AndroidJUnit4::class)
class DotReviewRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @org.junit.Before fun awaitApplicationReady() {
        val app = context.applicationContext as MinisApp
        if (!app.subsystemsReady()) ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            val end = SystemClock.uptimeMillis() + 10000
            while (!app.subsystemsReady() && SystemClock.uptimeMillis() < end) Thread.sleep(100)
            assertTrue(app.subsystemsReady())
        }
    }

    @Test fun nativeSendStopFocusedHeldAndTransitionGlyphsRemainReadable() {
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val contrastFailures = mutableListOf<String>()
        fun checkContrast(node: AccessibilityNodeInfo, label: String) {
            try { assertGlyphContrast(node, label) } catch (e: AssertionError) { contrastFailures += e.message.orEmpty() }
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            for (stop in listOf(false, true)) {
                scenario.onActivity { activity ->
                    activity.setContent { MinisTheme(darkTheme = InstrumentationRegistry.getArguments().getString("palette") == "dark") { Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.safeDrawingPadding().padding(32.dp)) { DotChatChrome(stop, !stop, { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() }) }
                    } } }
                }
                Thread.sleep(300)
                val label = context.getString(if (stop) R.string.nm_coding_stop else R.string.nm_coding_send)
                val action = awaitNode(label)
                assertTarget(action, label)
                capture("$label-idle")
                if (!action.isFocused) key(KeyEvent.KEYCODE_TAB)
                Thread.sleep(250)
                val focused = awaitNode(label)
                assertTrue("Actual native keyboard focus reaches $label", focused.isFocused)
                capture("$label-focused")
                checkContrast(focused, "$label keyboard focus")
                val bounds = Rect().also { focused.getBoundsInScreen(it) }
                val down = SystemClock.uptimeMillis()
                pointer(MotionEvent.ACTION_DOWN, bounds, down)
                try {
                    // Samples during the native press/focus transition, then its held endpoint.
                    for (delay in listOf(20L, 35L, 65L, 130L)) {
                        Thread.sleep(delay)
                        capture("$label-held-${SystemClock.uptimeMillis() - down}")
                        checkContrast(awaitNode(label), "$label held transition")
                    }
                    assertEquals("Held pointer has not invoked the real callback", if (stop) 1 else 0, callbacks.get())
                } finally { pointer(MotionEvent.ACTION_UP, bounds, down) }
                Thread.sleep(150)
                assertEquals("Native pointer release invokes only the supplied callback", if (stop) 2 else 1, callbacks.get())
            }
        }
        assertTrue("Native action contrast failures: ${contrastFailures.joinToString()}", contrastFailures.isEmpty())
    }

    @Test fun productionGoalsLabelsCompletionAndAddUseNativeActions() {
        val store = GoalStore.get(context)
        val id = "task3-review-goal-${SystemClock.uptimeMillis()}"
        val title = "Task3 review fixture goal"
        val file = File(context.filesDir, "minis-global/nanomuse/goals.json")
        val before = file.takeIf { it.exists() }?.readBytes()
        val goal = Goal(id = id, title = title)
        store.upsert(goal)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MinisTheme {
                    GoalsTab({}, {}, {}, {}, {}, {})
                } } }
                Thread.sleep(500)
                capture("goal-baseline-before-label-assertion")
                val label = localized("nm_goal_completion", "Goal completed: %1\$s", title)
                val completion = requireNotNull(find(label)) { "Goal completion has its actual localized name" }
                assertTarget(completion, "Goal completion")
                assertTrue("Actual completion is a native checkable control", completion.isCheckable)
                assertTrue("Completion is enabled", completion.isEnabled)
                assertFalse("Stored ACTIVE goal is unchecked", completion.isChecked)
                capture("goal-active-before-native-pointer")
                touch(completion)
                await { store.get(id)?.status == GoalStatus.DONE }
                val done = requireNotNull(find(label))
                assertTrue("Actual stored DONE goal is checked", done.isChecked)
                capture("goal-done-after-native-pointer")
                touch(done)
                await { store.get(id)?.status == GoalStatus.ACTIVE }
                assertFalse("Actual stored ACTIVE goal becomes unchecked", requireNotNull(find(label)).isChecked)
                val addLabel = localized("nm_task_add_section", "Add to %1\$s", context.getString(R.string.nm_goals_tracking))
                val add = requireNotNull(find(addLabel)) { "Tracking Add has a localized action name" }
                assertTarget(add, "Goals section Add")
                touch(add)
                Thread.sleep(350)
                capture("goals-add-scrolls-to-real-categories")
                assertNotNull("Real category section becomes visible", find(context.getString(R.string.nm_goal_cat_health)))
            }
        } finally {
            // Remove only this fixture goal; preserve every ordinary row and original file bytes.
            check(store.get(id)?.title == title)
            store.delete(id)
            if (before == null) file.delete() else file.writeBytes(before)
            store.reload()
        }
    }

    @Test fun productionFeedLikeHasNativeCheckedStateAndPersistsActualPost() {
        val app = context.applicationContext as MinisApp
        val file = File(context.filesDir, "minis-global/nanomuse/feed/2099-12-31/99.md")
        check(!file.exists()) { "Unique fixture post must not replace any ordinary post" }
        val post = FeedPost("2099-12-31", 99, "Task3 review fixture post", "note", "", "A local native Like fixture.", emptyList(), System.currentTimeMillis(), false, file)
        // Rendering FeedTab must not create a model-bound session/routine on this fixture baseline.
        assertTrue("Native feed fixture has no configured visible provider entries", app.providerRepository.allVisibleEntries().isEmpty())
        file.parentFile?.mkdirs(); file.writeText(post.serialize()); FeedStore.reload()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MinisTheme { FeedTab({}, {}, {}, {}) } } }
                Thread.sleep(600)
                capture("feed-baseline-before-label-assertion")
                val label = localized("nm_feed_like_post", "Like post: %1\$s", post.title)
                val like = requireNotNull(find(label)) { "Feed Like has its localized post-specific action name" }
                assertTarget(like, "Feed Like")
                assertTrue("Actual Like is native checkable", like.isCheckable)
                assertTrue("Like is enabled", like.isEnabled)
                assertFalse("Actual unliked post is unchecked", like.isChecked)
                assertTarget(awaitNode(localized("nm_feed_post_info", "Post information: %1\$s", post.title)), "Feed post information")
                capture("feed-unliked-before-native-pointer")
                touch(like)
                await { FeedPost.parse(file)?.liked == true }
                assertTrue("Actual persisted liked post is checked", requireNotNull(find(label)).isChecked)
                capture("feed-liked-after-native-pointer")
                touch(requireNotNull(find(label)))
                await { FeedPost.parse(file)?.liked == false }
                assertFalse("Actual persisted unlike is unchecked", requireNotNull(find(label)).isChecked)
            }
        } finally {
            check(FeedPost.parse(file)?.title == post.title)
            file.delete(); file.parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
            FeedStore.reload()
        }
    }

    @Test fun productionCloudModeAndUsageTabsHaveNativeSelectedStateAndTargets() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MinisTheme {
                io.github.nanomuse.ui.cloud.CloudSignInScreen({}, {})
            } } }
            Thread.sleep(500)
            val codeLabel = context.getString(R.string.nm_cloud_mode_code)
            val passwordLabel = context.getString(R.string.nm_cloud_mode_password)
            val code = awaitNode(codeLabel)
            val password = awaitNode(passwordLabel)
            assertTarget(code, "Cloud code mode"); assertTarget(password, "Cloud password mode")
            capture("cloud-mode-before-native-pointer")
            assertTrue("Actual code mode is natively selected", code.isSelected)
            touch(password)
            assertTrue("Actual password mode is natively selected", awaitNode(passwordLabel).isSelected)
            assertFalse("Actual code mode is not selected", awaitNode(codeLabel).isSelected)
            capture("cloud-password-selected")
        }
        val app = context.applicationContext as MinisApp
        val repo = app.providerRepository
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val keys = listOf("cloud.base", "cloud.instance_id", "cloud.hint", "cloud.channel", "cloud.usage_json")
        val old = keys.associateWith { prefs.getString(it, null) }
        val id = "task3-review-cloud-${SystemClock.uptimeMillis()}"
        try {
            // A cached account on a refused local socket exercises the real offline fallback.
            // No remote login/model endpoint is used and no model entry/default group is added.
            repo.addInstance(com.openminis.app.data.model.ProviderInstance(id, "Local tab fixture",
                com.openminis.app.data.model.ProviderType.openAI, com.openminis.app.data.model.ProviderCredential.apiKey,
                customBaseURL = "http://127.0.0.1:9", appendV1Suffix = false))
            repo.saveApiKey(id, "task3-review-local-placeholder")
            prefs.edit().putString("cloud.base", "http://127.0.0.1:9").putString("cloud.instance_id", id)
                .putString("cloud.hint", "Local tab fixture").putString("cloud.channel", "email")
                .putString("cloud.usage_json", "{\"today\":{\"by_kind\":[]},\"total\":{\"by_kind\":[]}}") .commit()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MinisTheme {
                    io.github.nanomuse.ui.cloud.CloudAccountScreen({}, {}, {}, {})
                } } }
                Thread.sleep(500)
                val todayLabel = context.getString(R.string.nm_cloud_usage_today)
                val totalLabel = context.getString(R.string.nm_cloud_usage_total)
                scrollFind(todayLabel)
                scrollFind(totalLabel)
                Thread.sleep(400) // Native scrolling must settle before retaining pointer bounds.
                val today = awaitNode(todayLabel)
                val total = awaitNode(totalLabel)
                assertTarget(today, "Cloud today usage"); assertTarget(total, "Cloud total usage")
                capture("cloud-usage-before-native-pointer")
                assertTrue("Actual today usage is selected", today.isSelected)
                touch(awaitNode(totalLabel))
                capture("cloud-usage-after-native-pointer")
                await { find(totalLabel)?.isSelected == true }
                assertTrue("Actual all-time usage is selected", awaitNode(totalLabel).isSelected)
                assertFalse("Actual today usage is no longer selected", awaitNode(todayLabel).isSelected)
                capture("cloud-total-usage-selected")
            }
        } finally {
            repo.removeInstance(id)
            check(repo.instance(id) == null)
            prefs.edit().apply { old.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
        }
    }

    private fun scrollFind(label: String): AccessibilityNodeInfo {
        repeat(10) { step ->
            val target = find(label, visibleOnly = false)
            val root = requireNotNull(automation.rootInActiveWindow)
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                for (i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { return it } }
                return node.takeIf { it.isScrollable }
            }
            val scroll = requireNotNull(scrollable(root))
            val viewport = Rect().also { scroll.getBoundsInScreen(it) }
            val bounds = target?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
            if (target != null && target.isVisibleToUser && bounds != null && viewport.contains(bounds)) return target
            capture("cloud-scroll-$step-before")
            // A full-page accessibility scroll can jump a partly visible tab above the viewport.
            // Move a short distance through real native touch input instead, choosing from bounds.
            val delta = (64 * context.resources.displayMetrics.density) * if (bounds != null && bounds.top < viewport.top) 1 else -1
            val down = SystemClock.uptimeMillis()
            fun event(kind: Int, y: Float) {
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, viewport.exactCenterX(), y, 0)
                motion.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(motion, true)) } finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, viewport.exactCenterY())
            try { repeat(12) { i -> Thread.sleep(25); event(MotionEvent.ACTION_MOVE, viewport.exactCenterY() + delta * (i + 1) / 12) } }
            finally { event(MotionEvent.ACTION_UP, viewport.exactCenterY() + delta) }
            Thread.sleep(400)
        }
        error("Whole native target did not enter viewport: $label")
    }

    private fun localized(key: String, fallback: String, argument: String): String {
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        return if (id == 0) String.format(fallback, argument) else context.getString(id, argument)
    }
    private fun find(label: String, visibleOnly: Boolean = true): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((!visibleOnly || node.isVisibleToUser) && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                var action = node
                while (!action.isClickable && !action.isSelected && action.parent != null) action = action.parent
                return action
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { return it } }
            return null
        }
        return automation.rootInActiveWindow?.let { walk(it) }
    }
    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 3000
        while (find(label) == null && SystemClock.uptimeMillis() < end) Thread.sleep(50)
        return requireNotNull(find(label)) { "Actual native node ready: $label" }
    }
    private fun assertTarget(node: AccessibilityNodeInfo, label: String) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val min = (48 * context.resources.displayMetrics.density).toInt()
        assertTrue("$label whole native actionable bounds>=48dp: $bounds", bounds.width() >= min && bounds.height() >= min)
        var ancestor = node.parent
        while (ancestor != null && !ancestor.isScrollable) ancestor = ancestor.parent
        val viewport = Rect().also { (ancestor ?: requireNotNull(automation.rootInActiveWindow)).getBoundsInScreen(it) }
        assertTrue("$label whole native target is within its viewport: $bounds in $viewport", viewport.contains(bounds))
    }
    private fun key(code: Int) {
        val down = SystemClock.uptimeMillis()
        for (kind in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            assertTrue(automation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), kind, code, 0), true))
    }
    private fun pointer(kind: Int, bounds: Rect, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun touch(node: AccessibilityNodeInfo) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, bounds, down); pointer(MotionEvent.ACTION_UP, bounds, down)
        Thread.sleep(150)
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 3000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(50)
        assertTrue("Actual production state changes within3seconds", condition())
    }
    private fun capture(name: String) {
        val dir = File(context.getExternalFilesDir(null), "dot-review-round1").apply { mkdirs() }
        val frame = requireNotNull(automation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
        val dump = StringBuilder()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            dump.appendLine("${" ".repeat(depth)}${node.className} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable} enabled=${node.isEnabled} checked=${node.isChecked} checkable=${node.isCheckable} selected=${node.isSelected} focused=${node.isFocused} bounds=$bounds")
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { walk(it, 0) }
        File(dir, "$name.txt").writeText(dump.toString())
    }
    private fun assertGlyphContrast(node: AccessibilityNodeInfo, label: String) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val frame = requireNotNull(automation.takeScreenshot())
        val density = context.resources.displayMetrics.density
        val radius = (8 * density).toInt()
        fun luminance(pixel: Int): Double {
            fun channel(v: Int): Double { val s = v / 255.0; return if (s <= .04045) s / 12.92 else Math.pow((s + .055) / 1.055, 2.4) }
            return .2126 * channel(android.graphics.Color.red(pixel)) + .7152 * channel(android.graphics.Color.green(pixel)) + .0722 * channel(android.graphics.Color.blue(pixel))
        }
        val background = luminance(frame.getPixel(bounds.centerX(), bounds.centerY() - (16 * density).toInt()))
        var glyphPixels = 0
        for (y in bounds.centerY() - radius until bounds.centerY() + radius) for (x in bounds.centerX() - radius until bounds.centerX() + radius) {
            val v = luminance(frame.getPixel(x, y))
            if ((maxOf(v, background) + .05) / (minOf(v, background) + .05) >= 4.5) glyphPixels++
        }
        frame.recycle()
        assertTrue("$label actual central glyph pixels at4.5:1 contrast: $glyphPixels", glyphPixels >= 8)
    }
}
