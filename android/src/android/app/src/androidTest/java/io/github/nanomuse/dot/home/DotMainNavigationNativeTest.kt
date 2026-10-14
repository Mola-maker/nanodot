package io.github.nanomuse.dot.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.LocaleManager
import android.app.UiAutomation
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.settings.getAppearancePrefs
import io.github.nanomuse.avatar.AvatarStore
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.home.MainChat
import io.github.nanomuse.ui.avatar.AgentMood
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Real launcher / MainActivity route on an already signed-out, provider-free installation.
 * Does not mark onboarding complete, create a conversation, install a provider or send a prompt.
 * All navigation and scrolling goes through InputDispatcher. Screenshots supplement state
 * assertions; the welcome avatar image is retained for visual review, not a pixel-match claim.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DotMainNavigationNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as MinisApp
    private val automation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )
    private val inject by lazy {
        UiAutomation::class.java.getMethod("injectInputEvent", InputEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
    }
    private lateinit var evidence: File
    private val actions = JSONArray()
    private data class PreferenceChange(val before: Any?, val after: Any?, val existed: Boolean, val exists: Boolean)
    private val gesturePreferenceChanges = mutableMapOf<String, PreferenceChange>()
    private var gestureLocales: android.os.LocaleList? = null

    @Test
    fun welcomePrimaryRoutesAndLanguageSettingsReturnWithoutChangingConfiguration() {
        evidence = File(context.cacheDir, "main-navigation-native-${SystemClock.uptimeMillis()}")
            .apply { check(mkdirs()) }
        val appearance = getAppearancePrefs(context)
        val originalAppearance = appearance.all.toMap()
        val localeManager = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java) else null
        val originalLocales = localeManager?.applicationLocales
        val originalFlags = automation.serviceInfo.flags
        var ready = false
        var succeeded = false
        var configurationChanged = false
        var primaryFailure: Throwable? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking { withTimeout(10_000) { app.providerRepository.awaitConfigLoaded() } }
            assertFalse("This route requires the existing signed-out state", NanoMuseCloud.isSignedIn(context))
            assertTrue("This route requires zero existing providers; it never edits providers",
                app.providerRepository.config.value.instances.isEmpty())
            val originalProviderConfig = app.providerRepository.config.value
            val initialSessions = sessions()
            val setup = context.getSharedPreferences("nanomuse", 0)
            val initialSetup = setup.all.filterKeys { it.startsWith("setup.") }
            val initialAvatar = AvatarStore.current.value

            val launcher = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals("Use the application's real launcher action", Intent.ACTION_MAIN, launcher.action)
            assertTrue("Use the application's real launcher category", launcher.hasCategory(Intent.CATEGORY_LAUNCHER))
            context.startActivity(launcher)
            awaitWelcome()
            ready = true
            await("The normal home session has resolved") { MainChat.sessionId.value != null }
            val homeSession = MainChat.sessionId.value
            capture("01-welcome")
            assertPrimaryRoutes()
            assertVisible(label(R.string.nm_welcome_email), clickable = true)
            assertVisible(label(R.string.nm_welcome_own_key), clickable = true)
            recordAvatarSelection()

            tap(label(R.string.nm_tab_tasks))
            awaitSelected(R.string.nm_tab_tasks)
            for (resource in listOf(R.string.nm_tab_feed, R.string.nm_tab_ideas,
                R.string.nm_tab_goals, R.string.nm_task_library)) {
                assertVisible(label(resource), clickable = true)
            }
            tap(label(R.string.nm_task_library))
            awaitSelected(R.string.nm_task_library)
            assertVisible(label(R.string.nm_library_artifacts))
            assertVisible(label(R.string.nm_library_media))
            capture("02-tasks-library")
            back()
            awaitWelcome()
            capture("03-library-back-welcome")
            tap(label(R.string.nm_tab_tasks))
            awaitSelected(R.string.nm_task_library)
            capture("04-library-selection-retained")
            // Exercise the actual primary Chat target, separately from system Back.
            tap(label(R.string.nm_tab_chat))
            awaitWelcome()

            tap(label(R.string.nm_devices_title))
            awaitVisible(R.string.nm_cloud_not_signed_in)
            assertVisible(label(R.string.nm_cloud_title))
            assertVisible(label(R.string.nm_cloud_sign_in), clickable = true)
            assertNoPrimaryTasks()
            capture("05-devices-signed-out")
            back()
            awaitWelcome()

            // Match visible Text, not the identically labelled welcome settings gear.
            tap(label(R.string.nm_drawer_settings), textOnly = true)
            awaitSettings()
            assertNoPrimaryTasks()
            capture("06-settings")
            scrollTo(label(R.string.settings_section_appearance), clickable = true)
            tap(label(R.string.settings_section_appearance))
            // Dot settings is the first section; Theme follows several screens of content.
            awaitVisible(R.string.nm_dot_show_orb)
            assertVisible(label(R.string.appearance_title))
            capture("07-appearance")
            scrollTo("English", clickable = true)
            assertVisible("English", clickable = true)
            capture("08-language-english")
            scrollTo("简体中文", clickable = true)
            assertVisible("简体中文", clickable = true)
            capture("09-language-chinese")
            // Read the language choices without selecting one or recreating the Activity.
            assertEquals("Reading language choices must preserve appearance settings",
                originalAppearance, appearance.all)
            back()
            awaitSettings()
            assertNoPrimaryTasks()
            capture("10-appearance-back-settings")
            back()
            awaitWelcome()
            assertPrimaryRoutes()
            capture("11-settings-back-welcome")

            assertEquals("Navigation must preserve provider configuration", originalProviderConfig,
                app.providerRepository.config.value)
            assertEquals("Navigation must not create or edit conversations", initialSessions, sessions())
            assertEquals("Navigation must preserve onboarding", initialSetup,
                setup.all.filterKeys { it.startsWith("setup.") })
            assertEquals("The same home session remains selected", homeSession, MainChat.sessionId.value)
            assertTrue("Navigation must preserve a selected custom avatar", initialAvatar === AvatarStore.current.value)
            assertFalse("Navigation must remain signed out", NanoMuseCloud.isSignedIn(context))
            succeeded = true
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { capture("failure") }
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun failedCleanup(failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
                event("cleanup-failure", failure.javaClass.simpleName)
            }
            // Normally no preferences change. Retain unexpected changes as a failure before
            // restoring them, so a misplaced physical swipe cannot leave the user altered.
            configurationChanged = appearance.all != originalAppearance ||
                (localeManager != null && localeManager.applicationLocales != originalLocales)
            if (configurationChanged) {
                event("unexpected-appearance-change", "restore only keys changed during a test gesture")
                runCatching {
                    restoreGesturePreferences(appearance)
                    if (localeManager != null && originalLocales != null && gestureLocales != null &&
                        localeManager.applicationLocales == gestureLocales) {
                        localeManager.applicationLocales = originalLocales
                    }
                }.onFailure(::failedCleanup)
            }
            if (ready) {
                // Do not finish MainActivity: leave the normal welcome page for the user.
                runCatching {
                    repeat(4) {
                        if (welcomeVisible()) return@runCatching
                        back()
                    }
                    assertTrue("Cleanup returns to the real welcome page", welcomeVisible())
                }.onFailure(::failedCleanup)
                runCatching { capture("12-final-welcome") }.onFailure(::failedCleanup)
            }
            val finalWelcome = ready && welcomeVisible()
            if (ready && !finalWelcome) failedCleanup(AssertionError("Final welcome page is not visible"))
            runCatching {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }.onFailure(::failedCleanup)
            File(evidence, "actions.json").writeText(actions.toString(2))
            File(evidence, "result.json").writeText(JSONObject()
                .put("passed", succeeded && !configurationChanged && cleanupFailure == null && finalWelcome)
                .put("proof", "native_user_flow")
                .put("entry", "package launcher to production MainActivity")
                .put("settingsRestored", appearance.all == originalAppearance)
                .put("unexpectedConfigurationChange", configurationChanged)
                .put("finalWelcomeVisible", finalWelcome)
                .put("cleanupFailed", cleanupFailure != null)
                .put("draftRetention", "not_run: existing provider-free welcome has no composer")
                .put("avatar", "selection/resource recorded; actual welcome PNG requires visual review")
                .toString(2))
            instrumentation.sendStatus(0, Bundle().apply { putString("mainNavigationEvidence", evidence.absolutePath) })
            if (primaryFailure == null && cleanupFailure != null) {
                throw AssertionError("Native home navigation cleanup failed", cleanupFailure)
            }
        }
        assertFalse("A navigation gesture must not change appearance settings", configurationChanged)
    }

    private fun sessions(): List<String> = runBlocking {
        withTimeout(10_000) { app.chatRepository.observeSessions().first() }
            .map { "${it.id}:${it.updatedAt}" }.sorted()
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun activity(): MainActivity = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()

    private fun label(resource: Int): String = main { activity().getString(resource) }

    private fun await(reason: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(reason)
    }

    private fun awaitVisible(resource: Int) = await("Visible destination: $resource") {
        hits(label(resource)).isNotEmpty()
    }

    private fun awaitSettings() = await("Settings replaces the real home shell") {
        hits(label(R.string.settings_title)).isNotEmpty() &&
            hits(label(R.string.nm_tab_tasks), actionable = true).isEmpty()
    }

    private fun welcomeVisible(): Boolean = runCatching {
        hits(label(R.string.nm_setup_title)).isNotEmpty() &&
            hits(label(R.string.nm_tab_chat), actionable = true).any { it.selected }
    }.getOrDefault(false)

    private fun awaitWelcome() {
        await("The real MainActivity must show the welcome page with Chat selected", ::welcomeVisible)
        assertVisible(label(R.string.nm_setup_title))
    }

    private fun awaitSelected(resource: Int) = await("Actual selected destination: $resource") {
        hits(label(resource), actionable = true).any { it.selected }
    }

    private fun assertPrimaryRoutes() {
        for (resource in listOf(R.string.nm_tab_chat, R.string.nm_tab_tasks,
            R.string.nm_devices_title, R.string.nm_drawer_settings)) {
            assertVisible(label(resource), clickable = true, textOnly = true)
        }
    }

    private fun assertNoPrimaryTasks() {
        assertTrue("A full settings/account destination replaces the home shell",
            hits(label(R.string.nm_tab_tasks), actionable = true).isEmpty())
    }

    private data class Hit(val bounds: Rect, val window: Rect, val windowId: Int,
        val selected: Boolean, val clickable: Boolean)

    /** Only the current focused APPLICATION window belonging to the production Activity. */
    private fun <T> inWindow(read: (AccessibilityNodeInfo, Rect, Int) -> T): T? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val windows = automation.windows
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused) continue
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() != context.packageName) continue
                    val bounds = Rect().also(window::getBoundsInScreen)
                    return read(root, bounds, window.id)
                } finally { root.recycle() }
            }
            return null
        } finally { windows.forEach { it.recycle() } }
    }

    private fun hits(text: String, actionable: Boolean = false, textOnly: Boolean = false): List<Hit> =
        inWindow { root, window, windowId ->
            val result = mutableListOf<Hit>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser && node.isEnabled && (node.text?.toString() == text ||
                        (!textOnly && node.contentDescription?.toString() == text))) {
                    var target = AccessibilityNodeInfo.obtain(node)
                    try {
                        if (actionable) {
                            while (!ownsActionOrSelection(target)) {
                                val parent = target.parent ?: break
                                target.recycle()
                                target = parent
                            }
                        }
                        val bounds = Rect().also(target::getBoundsInScreen)
                        if (target.isVisibleToUser && target.isEnabled && !bounds.isEmpty &&
                            window.contains(bounds) && (!actionable ||
                                (ownsActionOrSelection(target) && bounds != window &&
                                    !hasActionableDescendant(target)))) {
                            result += Hit(bounds, Rect(window), windowId, target.isSelected, target.isClickable)
                        }
                    } finally { target.recycle() }
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { visit(child) } finally { child.recycle() }
                }
            }
            visit(root)
            result.distinctBy { it.bounds }
        }.orEmpty()

    // Compose exposes an already chosen radio row as checked and removes its click action.
    // It still owns the language label's bounds. tap() separately requires isClickable.
    private fun ownsActionOrSelection(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isSelected || (node.isCheckable && node.isChecked)

    // A nearest clickable/selected semantics owner must describe this one target. A parent
    // containing other buttons cannot be substituted to manufacture a 48dp passing bound.
    private fun hasActionableDescendant(node: AccessibilityNodeInfo): Boolean {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (ownsActionOrSelection(child) || hasActionableDescendant(child)) return true
            } finally { child.recycle() }
        }
        return false
    }

    private fun assertVisible(text: String, clickable: Boolean = false, textOnly: Boolean = false): Hit {
        val hit = hits(text, clickable, textOnly).maxByOrNull { it.bounds.bottom }
        assertNotNull("Visible complete native target: $text", hit)
        val found = requireNotNull(hit)
        if (clickable) {
            val minimum = main { 48f * activity().resources.displayMetrics.density }
            assertTrue("48dp complete target for $text: ${found.bounds}",
                found.bounds.width() >= minimum - 1 && found.bounds.height() >= minimum - 1)
        }
        return found
    }

    private fun tap(text: String, textOnly: Boolean = false) {
        val hit = assertVisible(text, clickable = true, textOnly = textOnly)
        assertTrue("The destination accepts a click: $text", hit.clickable)
        val prefsBefore = getAppearancePrefs(context).all.toMap()
        event("tap", text, hit.bounds)
        val down = SystemClock.uptimeMillis()
        pointer(MotionEvent.ACTION_DOWN, down, hit.bounds.exactCenterX(), hit.bounds.exactCenterY())
        SystemClock.sleep(60)
        pointer(MotionEvent.ACTION_UP, down, hit.bounds.exactCenterX(), hit.bounds.exactCenterY())
        SystemClock.sleep(350)
        assertGesturePreservedPreferences(prefsBefore)
    }

    private fun pointer(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertEquals("InputDispatcher accepted pointer event", true,
            inject.invoke(automation, event, true, false)) }
        finally { event.recycle() }
    }

    private fun back() {
        event("system-back", "Back")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(400)
    }

    private fun scrollTo(text: String, clickable: Boolean) {
        repeat(24) {
            val minimum = main { 48f * activity().resources.displayMetrics.density }
            if (hits(text, clickable).any { it.bounds.height() >= minimum - 1 }) return
            val scroll = inWindow { root, window, _ ->
                val candidates = mutableListOf<Rect>()
                fun visit(node: AccessibilityNodeInfo) {
                    if (node.isVisibleToUser && node.isScrollable) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (bounds.intersect(window) && !bounds.isEmpty) candidates += bounds
                    }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { visit(child) } finally { child.recycle() }
                    }
                }
                visit(root)
                candidates.maxByOrNull { it.width().toLong() * it.height() }
            }
            val bounds = requireNotNull(scroll) { "Visible native scroll container to reach $text" }
            val prefsBefore = getAppearancePrefs(context).all.toMap()
            event("swipe-up", text, bounds)
            // The left content gutter avoids sliders and switches while staying inside
            // the actual scroll container; appearance equality is checked afterwards.
            val x = bounds.left + main { 8f * activity().resources.displayMetrics.density }
            val start = bounds.top + bounds.height() * .80f
            val end = bounds.top + bounds.height() * .25f
            val down = SystemClock.uptimeMillis()
            pointer(MotionEvent.ACTION_DOWN, down, x, start)
            for (step in 1..12) {
                SystemClock.sleep(20)
                pointer(MotionEvent.ACTION_MOVE, down, x, start + (end - start) * step / 12)
            }
            pointer(MotionEvent.ACTION_UP, down, x, end)
            SystemClock.sleep(300)
            assertGesturePreservedPreferences(prefsBefore)
        }
        throw AssertionError("Cannot reach language/settings target with bounded native swipes: $text")
    }

    private fun capture(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        val screenshotWidth = bitmap.width
        val screenshotHeight = bitmap.height
        try { File(evidence, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
        val nodes = JSONArray()
        requireNotNull(inWindow { root, window, windowId ->
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (node.isVisibleToUser) nodes.put(JSONObject()
                    .put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
                    .put("class", node.className?.toString()).put("depth", depth)
                    .put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
                    .put("selected", node.isSelected).put("clickable", node.isClickable)
                    .put("checkable", node.isCheckable).put("checked", node.isChecked)
                    .put("enabled", node.isEnabled).put("scrollable", node.isScrollable))
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { visit(child, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, 0)
            val state = main {
                val activity = activity()
                JSONObject().put("activity", activity.javaClass.name)
                    .put("windowId", windowId).put("windowBounds", window.toShortString())
                    .put("screenshotWidth", screenshotWidth).put("screenshotHeight", screenshotHeight)
                    .put("locale", activity.resources.configuration.locales.toLanguageTags())
                    .put("fontScale", activity.resources.configuration.fontScale)
                    .put("density", activity.resources.displayMetrics.density)
                    .put("imeVisible", activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()))
                    .put("providers", app.providerRepository.config.value.instances.size)
                    .put("signedIn", NanoMuseCloud.isSignedIn(context))
                    .put("mainSessionSha256", MainChat.sessionId.value?.let(::sha256))
                    .put("nodes", nodes)
            }
            File(evidence, "$name.json").writeText(state.toString(2))
        }) { "Screenshot $name must have a matching focused MainActivity window state" }
        event("capture", name)
    }

    private fun recordAvatarSelection() {
        val idleBytes = context.resources.openRawResource(AgentMood.IDLE.drawable).use { it.readBytes() }
        File(evidence, "avatar-selection.json").writeText(JSONObject()
            .put("customSelected", AvatarStore.current.value != null)
            .put("builtInIdleResource", context.resources.getResourceEntryName(AgentMood.IDLE.drawable))
            .put("builtInIdleSha256", sha256(idleBytes))
            .put("welcomeCapture", "01-welcome.png")
            .put("visualAssertion", "manual review required; resource identity alone is not rendering proof")
            .toString(2))
    }

    private fun event(action: String, label: String, bounds: Rect? = null) {
        actions.put(JSONObject().put("uptimeMs", SystemClock.uptimeMillis()).put("action", action)
            .put("label", label).put("bounds", bounds?.toShortString()))
    }

    private fun sha256(value: String): String = sha256(value.toByteArray())
    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private fun assertGesturePreservedPreferences(before: Map<String, *>) {
        val after = getAppearancePrefs(context).all
        val changed = (before.keys + after.keys).filter { before[it] != after[it] || before.containsKey(it) != after.containsKey(it) }
        for (key in changed) {
            gesturePreferenceChanges[key] = PreferenceChange(before[key], after[key], before.containsKey(key), after.containsKey(key))
            event("gesture-changed-preference", key)
        }
        if (changed.isNotEmpty() && Build.VERSION.SDK_INT >= 33) {
            gestureLocales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        }
        assertTrue("Navigation must not change appearance preferences: $changed", changed.isEmpty())
    }

    private fun restoreGesturePreferences(prefs: SharedPreferences) {
        if (gesturePreferenceChanges.isEmpty()) return
        prefs.edit().apply {
            gesturePreferenceChanges.forEach { (key, change) ->
                // A later external edit has priority over this test's restoration.
                if (prefs.contains(key) != change.exists || prefs.all[key] != change.after) {
                    event("preserved-later-preference-edit", key)
                } else if (!change.existed) {
                    remove(key)
                } else {
                    when (val value = change.before) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            putStringSet(key, value as Set<String>)
                        }
                        null -> remove(key)
                        else -> error("Unsupported preference type for $key")
                    }
                }
            }
        }.commit().also { assertTrue("Restore only appearance values changed during test gestures", it) }
    }
}
