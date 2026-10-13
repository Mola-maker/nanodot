package io.github.nanomuse.dot.overlay

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.dot.DotPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import io.github.nanomuse.dot.DotForegroundMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Framework binding setup for an isolated, already-granted native test scene.
 * Raw settings are captured before UiAutomation; only this service is toggled, and all
 * settings/observation flags are restored even if setup or the test itself fails.
 */
internal class DotNativeServiceFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var originals: Map<String, String?>? = null
    private var oldFlags: Int? = null
    private var oldEnabled: Boolean? = null
    private var connected: MinisAccessibilityService? = null
    private var ownsScene = false
    private var receiptStarted = 0L
    private var receiptKey: String? = null
    private var lastFramework: DotNativeServiceState? = null
    private var frameworkObservedAt = 0L
    private fun readFramework(own: String): DotNativeServiceState {
        val state = DotNativeServiceState.read(shell("dumpsys accessibility"), own)
        lastFramework = state; frameworkObservedAt = SystemClock.uptimeMillis()
        return state
    }
    private fun receipt(phase: String, state: DotNativeServiceState? = lastFramework) {
        val local = onMain { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }
        fun field(value: Boolean?) = if (state?.valid == true) value.toString() else "unknown"
        val metadata = "phase=$phase parsed=${state?.valid == true} ownEnabled=${field(state?.enabled)} " +
            "ownBinding=${field(state?.binding)} ownCrashed=${field(state?.crashed)} localConnected=$local"
        if (metadata == receiptKey) return
        receiptKey = metadata
        val row = "DOT_NATIVE_SERVICE startUptime=$receiptStarted elapsedMs=${SystemClock.uptimeMillis() - receiptStarted} " +
            "pid=${android.os.Process.myPid()} frameworkObservedUptime=$frameworkObservedAt $metadata\n"
        println(row.trimEnd())
        java.io.File(context.cacheDir, "dot-native-service-fixture.txt").appendText(row)
    }
    private fun setting(key: String): String? = Settings.Secure.getString(context.contentResolver, key)
    private fun validate(key: String, value: String?) {
        require(key in setOf("enabled_accessibility_services", "accessibility_enabled"))
        require(value == null || if (key == "accessibility_enabled") value in setOf("0", "1")
            else value.split(':').all { it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) })
    }
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun restore(key: String, value: String?) {
        validate(key, value)
        // executeShellCommand uses Runtime.exec argv, not sh -c; validated values must
        // remain unquoted (including any nested-class '$' characters).
        if (value == null) shell("settings delete secure $key")
        else shell("settings put secure $key $value")
    }
    private fun onMain(check: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = check() }
        return result
    }
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (check()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, check())
    }
    fun connect() {
        check(originals == null)
        val raw = listOf("enabled_accessibility_services", "accessibility_enabled").associateWith(::setting)
        raw.forEach { (key, value) -> validate(key, value) }
        val own = ComponentName(context.packageName, MinisAccessibilityService::class.java.name).flattenToString()
        val granted = raw["enabled_accessibility_services"].orEmpty().split(':').filter(String::isNotEmpty)
        assumeTrue("Root must grant the real accessibility service before native QA", own in granted)
        assumeTrue("Root must unlock the isolated scene before native QA",
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                context.getSystemService(PowerManager::class.java).isInteractive)
        // JUnit still invokes @After after failed @Before: save restoration authority before
        // the first UiAutomation connection or framework mutation.
        originals = raw
        oldEnabled = DotPreferences(context).showOnAccessibilityConnected()
        oldFlags = automation.serviceInfo.flags
        receiptStarted = SystemClock.uptimeMillis(); receiptKey = null
        readFramework(own)
        receipt("before")
        if (!onMain { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }) {
            restore("enabled_accessibility_services", granted.filter { it != own }.joinToString(":").ifEmpty { null })
            receipt("remove_requested")
            try {
                await("Framework must acknowledge own service disabled before its single regrant") {
                    val state = readFramework(own)
                    receipt("await_disabled", state)
                    state.isDisabled && !onMain { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }
                }
            } catch (failure: AssertionError) {
                receipt("disabled_timeout"); throw failure
            }
            receipt("disabled_acknowledged")
            restore("enabled_accessibility_services", granted.joinToString(":"))
            restore("accessibility_enabled", "1")
            receipt("regrant_once")
        }
        try {
            await("Framework accessibility service must connect before testing owner behavior") {
                onMain {
                    MinisAccessibilityService.getInstance()?.takeIf { it.dotOverlay.isConnected }?.let {
                        connected = it; true
                    } ?: false
                }
            }
        } catch (failure: AssertionError) {
            readFramework(own)
            receipt("connect_timeout"); throw failure
        }
        readFramework(own)
        receipt("connected")
        instrumentation.runOnMainSync {
            val service = service()
            assertNull("Use an isolated scene without a mounted Dot panel", service.dotOverlay.panelView)
            assertFalse("Use an isolated scene without visible Care", service.dotInteraction.careSession.visible.value)
            assertNull("Use an isolated scene without a foreground binding", service.dotInteraction.foreground.state.value.binding)
            assertEquals("Use an isolated scene without an active foreground mode", DotForegroundMode.Idle,
                service.dotInteraction.foreground.state.value.mode)
            ownsScene = true
        }
    }
    fun service(): MinisAccessibilityService = requireNotNull(connected) { "Run connect from @Before first" }
    fun close() {
        val raw = originals ?: return
        try {
            instrumentation.runOnMainSync {
                connected?.takeIf { ownsScene }?.let { service ->
                    service.dotInteraction.close()
                    oldEnabled?.let(service.dotOverlay::setEnabled)
                    service.dotOverlay.refreshLockState()
                }
            }
        } finally {
            try {
                oldFlags?.let { flags ->
                    automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                    assertEquals("Restore native observation flags", flags, automation.serviceInfo.flags)
                }
            } finally {
                raw.forEach { (key, value) -> restore(key, value) }
                raw.forEach { (key, value) -> assertEquals("Restore raw $key", value, setting(key)) }
                originals = null; connected = null; oldEnabled = null; oldFlags = null; ownsScene = false
            }
        }
    }
}
