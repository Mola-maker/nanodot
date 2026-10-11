package io.github.nanomuse.dot.care

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.accessibility.MinisAccessibilityService
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only real framework bootstrap. Root owns killed-process system restoration. */
class CareNativeHarness : TestRule {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val runOwner = requireNotNull(arguments.getString("careOwner")).also {
        require(UUID.fromString(it).toString() == it)
    }
    private val source = requireNotNull(arguments.getString("careSource")).also {
        require(it.matches(Regex("[0-9a-f]{40}")))
    }
    private val ownService = ComponentName(instrumentation.targetContext.packageName,
        MinisAccessibilityService::class.java.name)
    private var previous: Map<String, String?>? = null
    private var originalFlags: Int? = null
    private var connection: UiAutomation? = null
    private lateinit var case: String
    private val owner: String get() = UUID.nameUUIDFromBytes("$runOwner:$case".toByteArray(Charsets.UTF_8)).toString()
    private var sequence = 0
    private var backupRequested = false
    private var backupReady = false
    private var clipboardRestored = false
    private var hostStarted = false
    val automation: UiAutomation get() = requireNotNull(connection)
    val service: MinisAccessibilityService get() = requireNotNull(MinisAccessibilityService.getInstance())

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            case = description.methodName
            var failure: Throwable? = null
            var performed = false
            try {
                stage("case_started")
                connect()
                base.evaluate()
                performed = true
                stage("assertions_passed")
            } catch (problem: Throwable) {
                failure = problem
                stage("case_failed", false)
            } finally {
                val errors = mutableListOf<Throwable>()
                val cleanup = Bundle()
                fun attempt(label: String, action: () -> Unit) {
                    try { action(); cleanup.putBoolean(label, true) }
                    catch (problem: Throwable) { cleanup.putBoolean(label, false); errors += problem }
                }
                // Close care windows in each body first; restoration needs focused framework host.
                if (backupRequested) attempt("clipboardRestore") { restoreClipboard() }
                if (hostStarted) attempt("hostBackDispatched") {
                    cleanup.putBoolean("hostBackOwnershipVerified", false)
                    cleanup.putBoolean("hostCleanupRequiresRoot", true)
                    instrumentation.runOnMainSync {
                        // Re-observe immediately before Back: a prior launch/ack does not own
                        // whatever window is focused now. Unknown focus is never navigated.
                        check(currentFocusedWindowIsOwnedHost()) { "Owned host not focused; root cleanup required" }
                        cleanup.putBoolean("hostBackOwnershipVerified", true)
                        check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                        cleanup.putBoolean("hostCleanupRequiresRoot", false)
                    }
                }
                originalFlags?.let { flags -> attempt("automationFlagsRestored") {
                    automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                    check(automation.serviceInfo.flags == flags)
                } }
                previous?.let { baseline ->
                    baseline.forEach { (key, value) -> attempt("restored_$key") { putSecure(key, value) } }
                    baseline.forEach { (key, value) -> attempt("verified_$key") { check(secure(key) == value) } }
                }
                val final = Bundle(cleanup).apply {
                    putBoolean("assertionsPerformed", performed)
                    putBoolean("backupRequested", backupRequested)
                    putBoolean("backupReady", backupReady)
                    putBoolean("clipboardPayloadRestored", clipboardRestored)
                    putBoolean("harnessRestorationSucceeded", errors.isEmpty())
                    putInt("restorationFailures", errors.size)
                    putBoolean("casePassed", failure == null && errors.isEmpty() && performed)
                    putBoolean("productionTripleHubProviderOperationCovered", false)
                }
                stage("case_finally", failure == null && errors.isEmpty(), final)
                errors.forEach { problem ->
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
        }
    }

    fun stage(name: String, ok: Boolean = true, fields: Bundle = Bundle()) {
        val status = Bundle(fields).apply {
            putString("careStage", name)
            putString("careCase", case)
            putString("careRunOwner", runOwner)
            putString("careOwner", owner)
            putString("careSourceClaim", source) // Root must match this claim to immutable APK provenance.
            putInt("careSequence", ++sequence)
            putBoolean("careOk", ok)
        }
        instrumentation.sendStatus(2, status)
    }

    private fun secure(key: String): String? = Settings.Secure.getString(
        instrumentation.targetContext.contentResolver, key)

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun putSecure(key: String, value: String?) {
        check(key == "enabled_accessibility_services" || key == "accessibility_enabled")
        // Feed a real shell through stdin: executeShellCommand itself does not parse quoting.
        // This preserves an originally present empty string, colons and literal '$' exactly.
        val pipes = automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { stream ->
            val command = if (value == null) "settings delete secure $key"
                else "settings put secure $key ${shellQuote(value)}"
            stream.write((command + "\nexit\n").toByteArray(Charsets.UTF_8))
        }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        check(secure(key) == value) { "Secure setting write failed" }
    }

    fun awaitMain(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) SystemClock.sleep(50)
        }
        check(ready) { description }
    }

    private fun connect() {
        check(android.os.Build.VERSION.SDK_INT >= 35) { "Harness public ClipData contract requires API 35+" }
        previous = listOf("enabled_accessibility_services", "accessibility_enabled").associateWith(::secure)
        connection = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var ready = false
        instrumentation.runOnMainSync { ready = MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true }
        if (!ready) {
            val services = previous!!.getValue("enabled_accessibility_services").orEmpty().split(':')
            // Compare canonical ComponentName while retaining every unrelated segment verbatim.
            val others = services.filter { ComponentName.unflattenFromString(it) != ownService }
            putSecure("enabled_accessibility_services", others.joinToString(":"))
            awaitMain("Real service must disconnect before rebind") {
                MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected != true
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var unbound = false
            while (!unbound && SystemClock.uptimeMillis() < deadline) {
                val state = ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("dumpsys accessibility")).use { it.readBytes().toString(Charsets.UTF_8) }
                val bindingLines = state.lineSequence().filter {
                    it.contains("Bound services:") || it.contains("Binding services:")
                }.toList()
                unbound = bindingLines.isNotEmpty() && bindingLines.none { it.contains("MinisAccessibilityService") }
                if (!unbound) SystemClock.sleep(50)
            }
            check(unbound) { "Framework binding did not leave enabled services" }
            // Retain the exact unrelated service string, including any empty segments.
            val retained = others.joinToString(":")
            putSecure("enabled_accessibility_services",
                if (retained.isEmpty()) ownService.flattenToString()
                else retained + ":" + ownService.flattenToString())
            putSecure("accessibility_enabled", "1")
        }
        awaitMain("Actual framework service connection on Main") {
            MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected == true
        }
        stage("framework_service_connected", fields = Bundle().apply {
            putBoolean("actualServiceConnected", true)
            putInt("targetPid", android.os.Process.myPid())
        })
        host("smoke")
        awaitHost()
    }

    fun awaitHost() {
        awaitMain("Real focused/active host and connected scene must agree") {
            val scene = service.dotScreen.currentSceneKey()
            val id = service.dotScreen.hostWindowId()
            val windows = service.windows
            try {
                scene?.packageName == instrumentation.context.packageName && id != null &&
                    service.dotScreen.hostViewport(id) != null && windows.any { candidate ->
                        val root = candidate.root
                        try {
                            candidate.id == id && candidate.isActive && candidate.isFocused &&
                                root?.packageName?.toString() == instrumentation.context.packageName
                        } finally { root?.recycle() }
                    }
            } finally { windows.forEach { it.recycle() } }
        }
        stage("host_scene_observed", fields = Bundle().apply {
            putBoolean("actualHostActiveFocused", true)
        })
    }

    private fun currentFocusedWindowIsOwnedHost(): Boolean {
        automation.clearCache()
        fun hasPublicTarget(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.contentDescription?.toString() == "CARE_TOUCH_TARGET") return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (hasPublicTarget(child)) return true } finally { child.recycle() }
            }
            return false
        }
        val activeRoot = automation.rootInActiveWindow ?: return false
        try {
            if (activeRoot.packageName?.toString() != instrumentation.context.packageName ||
                !hasPublicTarget(activeRoot)) return false
            val windows = automation.windows
            try {
                return windows.any { candidate ->
                    if (!candidate.isActive || !candidate.isFocused || candidate.id != activeRoot.windowId) false
                    else {
                        val root = candidate.root
                        try {
                            root != null && root.packageName?.toString() == instrumentation.context.packageName &&
                                hasPublicTarget(root)
                        } finally { root?.recycle() }
                    }
                }
            } finally { windows.forEach { it.recycle() } }
        } finally { activeRoot.recycle() }
    }

    private fun host(operation: String): Bundle {
        val request = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                response = Bundle(resultData)
                latch.countDown()
            }
        }
        hostStarted = true
        instrumentation.context.startActivity(Intent(instrumentation.context, CareOverlayFixture::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("careOp", operation).putExtra("careOwner", owner).putExtra("careRequest", request)
            .putExtra("careSource", source).putExtra("careReply", receiver))
        check(latch.await(5, TimeUnit.SECONDS)) { "Framework host acknowledgement missing; root recovery required" }
        val status = requireNotNull(response)
        check(status.getString("owner") == owner && status.getString("request") == request &&
            status.getString("source") == source && status.getString("operation") == operation)
        stage("host_$operation", status.getBoolean("ok"), status)
        check(status.getBoolean("ok") && status.getBoolean("hostWindowFocused")) { "Framework host operation failed" }
        return status
    }

    fun backupClipboard() {
        check(!backupRequested)
        backupRequested = true // Even a missing reply requires recovery; Copy remains forbidden.
        host("backup")
        backupReady = true
        awaitHost()
    }

    fun restoreClipboard() {
        if (clipboardRestored) return
        check(backupRequested)
        host("restore")
        clipboardRestored = true
    }
}
