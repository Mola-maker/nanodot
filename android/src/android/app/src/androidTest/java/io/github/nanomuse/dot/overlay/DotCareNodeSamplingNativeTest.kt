package io.github.nanomuse.dot.overlay

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nanomuse.dot.DotInteractionCoordinator
import io.github.nanomuse.dot.care.CareState
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.screen.CaptureOutcome
import io.github.nanomuse.dot.screen.ScreenHostFixture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Owner-route fixture, not a keyboard, visual-understanding or provider acceptance test.
 * The break caught is Care requesting pixels (and their rate limit) for a node-only planner.
 * The real coordinator/controller must surface the node sampler's unavailable result once.
 * A secure, disposable host prevents the old direct screenshot route reaching a planner on RED.
 */
@RunWith(AndroidJUnit4::class)
class DotCareNodeSamplingNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val nativeService = DotNativeServiceFixture()
    private val automation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Before fun connect() {
        assumeTrue("Secure window screenshot refusal is explicit on API 34+", Build.VERSION.SDK_INT >= 34)
        nativeService.connect()
    }

    @After fun restoreService() { nativeService.close() }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(16)
        }
        fail(message)
    }

    @Suppress("DEPRECATION")
    private fun fixtureVisible(): Boolean {
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return false
        return try {
            if (root.packageName?.toString() != instrumentation.context.packageName) false
            else {
                val matches = root.findAccessibilityNodeInfosByText("CARE_NODE_ROUTE_FIXTURE")
                try { matches.any { it.isVisibleToUser && it.text?.toString() == "CARE_NODE_ROUTE_FIXTURE" } }
                finally { matches.forEach { it.recycle() } }
            }
        } finally { root.recycle() }
    }

    @Test fun careOwnerUsesFreshNodesAndDoesNotRetryUnavailableSample() {
        val service = nativeService.service()
        val evidence = File(instrumentation.targetContext.cacheDir,
            "care-node-route-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val nodeGenerations = mutableListOf<Long>()
        var screenCalls = 0
        var owner: DotInteractionCoordinator? = null
        var passed = false
        // This owner never opens/binds chat. Fail closed if an accidental chat route is added.
        val adapter = DotChatAdapter({ it }, { error("No chat session permitted") },
            { error("No screenshot attachment permitted") })
        try {
            instrumentation.context.startActivity(Intent(instrumentation.context, ScreenHostFixture::class.java)
                .putExtra("secure", true)
                .putExtra("marker", "CARE_NODE_ROUTE_FIXTURE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Secure external fixture must be the current host before submitting a goal") {
                fixtureVisible() &&
                    main { service.dotScreen.currentSceneKey()?.packageName == instrumentation.context.packageName }
            }
            val current = main {
                service.dotInteraction.disconnect()
                DotInteractionCoordinator(service, adapter,
                    screenCapture = { _, _ ->
                        screenCalls++
                        CaptureOutcome.Unavailable("fixture_unexpected_pixels")
                    },
                    nodeCapture = { generation ->
                        nodeGenerations += generation
                        CaptureOutcome.Unavailable("fixture_nodes_unavailable")
                    }).also { owner = it; it.connect() }
            }
            val generation = main {
                // Direct session entry deliberately isolates owner routing from keyboard/focus tests.
                current.careSession.submitGoal("Observe this local fixture.")
                current.careSession.controller.generation
            }
            await("Care must finish this single unavailable sample in Recovering") {
                main { current.careSession.controller.state.value.state == CareState.Recovering }
            }
            main {
                val state = current.careSession.controller.state.value
                assertEquals("Care must use the fresh-node sampler, not direct platform pixels",
                    "fixture_nodes_unavailable", state.errorCode)
                assertEquals("The sampler must receive the current goal generation",
                    listOf(generation), nodeGenerations)
                assertEquals("Node-only Care must not request an injected screenshot", 0, screenCalls)
                assertTrue("An unavailable sample must not produce a plan", state.proposal == null)
                assertTrue("An unavailable sample must never credit a step",
                    current.careSession.controller.verifiedSteps.value.isEmpty())
            }
            // No explicit reobserve or host mutation: recovery must not schedule an automatic retry.
            val settledUntil = SystemClock.uptimeMillis() + 300
            while (SystemClock.uptimeMillis() < settledUntil) {
                main {
                    assertEquals(listOf(generation), nodeGenerations)
                    assertEquals(0, screenCalls)
                    assertEquals(CareState.Recovering, current.careSession.controller.state.value.state)
                    assertEquals("fixture_nodes_unavailable",
                        current.careSession.controller.state.value.errorCode)
                }
                SystemClock.sleep(16)
            }
            passed = true
        } finally {
            val receipt = main {
                JSONObject().put("fixture", "owner_route_unavailable_nodes")
                    .put("passed", passed).put("nodeGenerations", JSONArray(nodeGenerations))
                    .put("screenCaptureSeamCalls", screenCalls)
                    .put("state", owner?.careSession?.controller?.state?.value?.state?.name)
                    .put("errorCode", owner?.careSession?.controller?.state?.value?.errorCode)
                    .put("verifiedSteps", owner?.careSession?.controller?.verifiedSteps?.value?.size)
                    .put("secureHost", true).put("settleMs", 300)
                    .put("wallClockMs", System.currentTimeMillis())
                    .put("uptimeMs", SystemClock.uptimeMillis())
            }
            try { File(evidence, "receipt.json").writeText(receipt.toString()) }
            finally {
                main {
                    owner?.disconnect()
                    service.dotInteraction.disconnect()
                    service.dotInteraction.connect()
                }
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }
}
