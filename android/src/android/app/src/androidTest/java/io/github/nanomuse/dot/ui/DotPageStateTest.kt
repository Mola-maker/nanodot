package io.github.nanomuse.dot.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import io.github.nanomuse.ui.home.HomeBus
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import io.github.nanomuse.ui.onboarding.FirstRunSetup
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

import androidx.activity.compose.setContent
import io.github.nanomuse.ui.library.LibraryTab
import io.github.nanomuse.ui.profile.AgentProfileScreen
import com.openminis.app.ui.theme.MinisTheme
import io.github.nanomuse.net.OwnProviderProxy
import io.github.nanomuse.ui.net.NetworkScreen
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.ui.cloud.CloudSignInScreen
/** Production page validation and retry, without a request to any provider. */
@RunWith(AndroidJUnit4::class)
class DotPageStateTest {
    @org.junit.Before fun awaitRealApplicationReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        if (!app.subsystemsReady()) {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                val deadline = SystemClock.uptimeMillis() + 10000
                while (!app.subsystemsReady() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
                assertTrue("Actual Application subsystems initialized before fixture setup", app.subsystemsReady())
            }
        }
    }

    @Test fun networkValidationShowsAccessibleRetryAndKeepsEnteredConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val old = OwnProviderProxy.config(context)
        // Invalid, keyless input exercises the real validation path before any IO.
        val fixture = OwnProviderProxy.Config(host = "localhost", port = 0)
        OwnProviderProxy.save(context, fixture)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                settle()
                scenario.onActivity { activity -> activity.setContent {
                    MinisTheme { NetworkScreen(onBack = {}) }
                } }
                settle()
                val test = requireNotNull(find(context.getString(R.string.nm_network_test)))
                touch(clickable(test), "network test")
                settle()
                val invalid = context.getString(R.string.nm_network_invalid)
                assertNotNull("Real validation error stays visible", find(invalid))
                capture("network-validation-before-retry")
                val retry = requireNotNull(find(context.getString(R.string.nm_avatar_retry))) { "Recoverable production validation exposes Retry" }
                val target = clickable(retry)
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                val min = (48 * context.resources.displayMetrics.density).toInt()
                assertTrue("Retry is at least 48dp: $bounds", bounds.width() >= min && bounds.height() >= min)
                touch(target, "Retry")
                settle()
                assertNotNull("Retry keeps validation visible until input is corrected", find(invalid))
                assertEquals("Retry retains actual host/port input", fixture, OwnProviderProxy.config(context))
                capture("network-validation-retry")
            }
        } finally { OwnProviderProxy.save(context, old) }
    }
    @Test fun productionLibrarySegmentsHaveNativeSelectedStateAnd48DpTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            settle()
            scenario.onActivity { activity -> activity.setContent {
                MinisTheme { LibraryTab(header = {}, chatRepository = app.chatRepository, onPreview = {}, onOpenSession = {}) }
            } }
            settle()
            val first = requireNotNull(find(context.getString(R.string.nm_library_artifacts)))
            assertTarget(first, "Artifacts")
            assertTrue("Actual selected Artifacts is announced", clickable(first).isSelected)
            val second = requireNotNull(find(context.getString(R.string.nm_library_media)))
            assertTarget(second, "Media")
            touch(clickable(second), "Media")
            settle()
            assertTrue("Actual selected Media is announced", clickable(requireNotNull(findImmediate(context.getString(R.string.nm_library_media)))).isSelected)
            capture("library-selected-media")
        }
    }

    @Test fun productionProfilePaneControlsHaveNativeSelectedStateAnd48DpTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MinisApp
        // ActivityScenario performs an unbounded Looper-idle wait around its launch.
        // An animated avatar continuously schedules frames; bound actual main-thread work instead.
        var activity: MainActivity? = null
        fun main(label: String, action: () -> Unit) {
            val completed = java.util.concurrent.CountDownLatch(1)
            var failure: Throwable? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { action() } catch (e: Throwable) { failure = e } finally { completed.countDown() }
            }
            assertTrue("Actual main thread responds during $label within 2 seconds",
                completed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            failure?.let { throw it }
        }
        main("native launch") {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (activity == null && SystemClock.uptimeMillis() < deadline) {
                main("Activity readiness") {
                    activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                if (activity == null) Thread.sleep(100)
            }
            main("profile composition") {
                requireNotNull(activity).setContent {
                    MinisTheme { AgentProfileScreen(app.chatRepository, {}, {}, {}, {}, {}, {}, {}, {}) }
                }
            }
            settle()
            val first = requireNotNull(find(context.getString(R.string.nm_profile_pane_activity)))
            assertTarget(first, "Activity")
            assertTrue("Actual profile Activity pane is selected", clickable(first).isSelected)
            val second = requireNotNull(find(context.getString(R.string.nm_profile_pane_approvals)))
            assertTarget(second, "Approvals")
            capture("profile-before-native-pointer")
            touch(clickable(second), "Approvals")
            val selectedDeadline = SystemClock.uptimeMillis() + 2000
            while (findImmediate(context.getString(R.string.nm_profile_pane_approvals))?.let { clickable(it).isSelected } != true
                && SystemClock.uptimeMillis() < selectedDeadline) Thread.sleep(50)
            assertTrue("Actual selected Approvals is announced", clickable(requireNotNull(findImmediate(context.getString(R.string.nm_profile_pane_approvals)))).isSelected)
            repeat(8) { main("animated profile control response") {}; Thread.sleep(100) }
            capture("profile-selected-approvals")
            if (InstrumentationRegistry.getArguments().getString("expectedReducedMotion") == "true") {
                val before = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                Thread.sleep(700)
                main("static profile response") {}
                val after = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val margin = (32 * context.resources.displayMetrics.density).toInt()
                val firstFrame = Bitmap.createBitmap(before, 0, margin, before.width, before.height - margin * 2)
                val secondFrame = Bitmap.createBitmap(after, 0, margin, after.width, after.height - margin * 2)
                assertTrue("Reduced-motion actual profile is static and responsive", firstFrame.sameAs(secondFrame))
                firstFrame.recycle(); secondFrame.recycle(); before.recycle(); after.recycle()
            }
        } finally { main("native Activity cleanup") { activity?.finish() } }
    }

    @Test fun productionNetworkCancelClosesDelayedTransportAndRetainsInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val old = OwnProviderProxy.config(context)
        val proxy = HeldProxy()
        val fixture = OwnProviderProxy.Config(host = "127.0.0.1", port = proxy.port)
        OwnProviderProxy.save(context, fixture)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                settle()
                scenario.onActivity { activity -> activity.setContent { MinisTheme { NetworkScreen(onBack = {}) } } }
                settle()
                touch(clickable(requireNotNull(find(context.getString(R.string.nm_network_test)))), "Test local held proxy")
                assertTrue("Actual OkHttp request reaches local proxy without forwarding", proxy.opened.await(8, java.util.concurrent.TimeUnit.SECONDS))
                settle()
                capture("network-running-before-cancel")
                val cancel = requireNotNull(find(context.getString(R.string.nm_cancel))) { "Real running Network probe exposes Cancel" }
                assertTarget(cancel, "Cancel")
                touch(clickable(cancel), "Cancel actual request")
                assertTrue("Cancel closes actual delayed transport", proxy.closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
                settle()
                assertEquals("Cancel retains entered host and port", fixture, OwnProviderProxy.config(context))
                assertTrue("Cancelled operation does not invent an error", findImmediate(context.getString(R.string.nm_avatar_mood_error)) == null)
                capture("network-cancelled-input-retained")
            }
        } finally { proxy.close(); OwnProviderProxy.save(context, old) }
    }

    /** Accepts the HTTPS CONNECT locally; never forwards to the public target. */
    private class HeldProxy : AutoCloseable {
        private val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val opened = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        @Volatile private var connection: java.net.Socket? = null
        private val worker = kotlin.concurrent.thread(isDaemon = true, name = "task3-held-proxy") {
            try {
                server.accept().use { socket ->
                    connection = socket
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) { val byte = reader.read(); check(byte >= 0); tail = (tail + byte.toChar()).takeLast(4) }
                    opened.countDown()
                    // Keep CONNECT unanswered: no external DNS, TLS or provider traffic.
                    while (reader.read() != -1) { }
                    closed.countDown()
                }
            } catch (_: java.io.IOException) { }
        }
        override fun close() { connection?.close(); server.close(); worker.join(1000) }
    }

    @Test fun realCloudRequestCancellationClosesDelayedTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val had = prefs.contains("cloud.base")
        val old = prefs.getString("cloud.base", null)
        val server = HeldProxy()
        NanoMuseCloud.setBaseUrl(context, "http://127.0.0.1:${server.port}")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
        val job = scope.launch { NanoMuseCloud.requestCode(context, "fixture") }
        try {
            assertTrue("Actual existing Cloud code request reaches bounded loopback", server.opened.await(8, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            assertTrue("Cancellation closes actual Cloud transport without waiting for timeout", server.closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Real Cloud coroutine is cancelled", job.isCancelled)
        } finally {
            server.close()
            job.cancel()
            kotlinx.coroutines.runBlocking { job.join() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            prefs.edit().apply { if (had) putString("cloud.base", old) else remove("cloud.base") }.commit()
        }
    }

    @Test fun productionCloudPageCancelPreservesIdentifierAndDoesNotInventError() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val had = prefs.contains("cloud.base")
        val old = prefs.getString("cloud.base", null)
        val server = HeldProxy()
        NanoMuseCloud.setBaseUrl(context, "http://127.0.0.1:${server.port}")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                settle()
                scenario.onActivity { activity -> activity.setContent { MinisTheme { CloudSignInScreen({}, {}) } } }
                settle()
                fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && node.isEditable) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { return it } }
                    return null
                }
                var field: AccessibilityNodeInfo? = null
                val fieldDeadline = SystemClock.uptimeMillis() + 10000
                while (field == null && SystemClock.uptimeMillis() < fieldDeadline) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    field = instrumentation.uiAutomation.rootInActiveWindow?.let { editable(it) }
                    if (field == null) Thread.sleep(100)
                }
                capture("cloud-identifier-ready")
                val identifierField = requireNotNull(field) { "Actual Cloud identifier becomes editable in the native viewport" }
                val input = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "fixture") }
                assertTrue("Actual editable identifier receives fixture input", identifierField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
                settle()
                touch(clickable(requireNotNull(find(context.getString(R.string.nm_cloud_send_code)))), "Send code to local fixture")
                assertTrue("Actual production page starts local request", server.opened.await(8, java.util.concurrent.TimeUnit.SECONDS))
                settle()
                capture("cloud-running-before-cancel")
                val cancel = requireNotNull(find(context.getString(R.string.nm_cancel))) { "Actual pending Cloud page exposes Cancel" }
                assertTarget(cancel, "Cloud Cancel")
                capture("cloud-cancel-target-before-pointer")
                touch(clickable(cancel), "Cloud Cancel")
                assertTrue("Cloud page cancel closes underlying request", server.closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
                settle()
                assertNotNull("Entered identifier is retained", find("fixture"))
                assertTrue("Cancelled page creates no error", findImmediate(context.getString(R.string.nm_avatar_mood_error)) == null)
                capture("cloud-cancelled-input-retained")
            }
        } finally {
            server.close()
            prefs.edit().apply { if (had) putString("cloud.base", old) else remove("cloud.base") }.commit()
        }
    }

    @Test fun productionAvatarCancelRetainsReadyPictureInputAndDropsLateCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MinisApp
        val repo = app.providerRepository
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val keys = listOf("avatar.provider_id", "avatar.model", "avatar.description", "avatar.style")
        val before = keys.associateWith { prefs.getString(it, null) }
        val directory = io.github.nanomuse.avatar.AvatarStore.candidatesDir()
        val originalFiles = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val baseFile = io.github.nanomuse.avatar.AvatarStore.baseFile()
        val baseBefore = baseFile.takeIf { it.exists() }?.readBytes()
        val selectedBefore = io.github.nanomuse.avatar.AvatarStudio.selected.value
        val providerId = "task3-image-${SystemClock.uptimeMillis()}"
        val server = LocalImages()
        val model = com.openminis.app.data.model.LLMModel("fixture-image", "Local image fixture", "openAI", outputModalities = listOf("image"))
        val entry = com.openminis.app.data.model.ModelEntry(providerId, model, isCustom = true, uuid = "$providerId/${model.id}")
        try {
            repo.addInstance(com.openminis.app.data.model.ProviderInstance(providerId, "Local image fixture",
                com.openminis.app.data.model.ProviderType.openAI, com.openminis.app.data.model.ProviderCredential.apiKey,
                customBaseURL = "http://127.0.0.2:${server.port}", appendV1Suffix = false))
            repo.addEntry(entry)
            repo.saveApiKey(providerId, "task3-fixture-image-token")
            io.github.nanomuse.avatar.ImageGen.save(context, providerId, model.id)
            assertTrue("Actual Avatar endpoint is the bounded fixture; ready=${app.subsystemsReady()}, eligible=${io.github.nanomuse.avatar.ImageGen.eligibleInstances(context).any { it.id == providerId }}, stored=${repo.instance(providerId) != null}, hasFixtureToken=${repo.loadApiKey(providerId) != null}", io.github.nanomuse.avatar.ImageGen.endpoint(context)?.instanceId == providerId)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                settle()
                scenario.onActivity { activity -> activity.setContent {
                    MinisTheme { io.github.nanomuse.ui.avatar.AvatarStudioScreen({}, {}, {}) }
                } }
                scenario.onActivity { assertTrue(io.github.nanomuse.avatar.AvatarStudio.generateCandidates(context, "Retained fixture description", io.github.nanomuse.avatar.AvatarStudio.Style.FLAT)) }
                assertTrue("Actual image transport reaches only loopback", server.started.await(8, java.util.concurrent.TimeUnit.SECONDS))
                val readyDeadline = SystemClock.uptimeMillis() + 6000
                while (io.github.nanomuse.avatar.AvatarStudio.slots.value.none { it is io.github.nanomuse.avatar.AvatarStudio.Slot.Ready } && SystemClock.uptimeMillis() < readyDeadline) Thread.sleep(100)
                val ready = io.github.nanomuse.avatar.AvatarStudio.slots.value.filterIsInstance<io.github.nanomuse.avatar.AvatarStudio.Slot.Ready>()
                assertEquals("One real candidate completed before Cancel", 1, ready.size)
                val readyBytes = ready.single().file.readBytes()
                settle()
                capture("avatar-running-before-cancel")
                val cancel = requireNotNull(find(context.getString(R.string.nm_cancel))) { "Actual owned avatar operation exposes Cancel" }
                assertTarget(cancel, "Avatar Cancel")
                touch(clickable(cancel), "Avatar Cancel")
                settle()
                assertTrue("Actual owned jobs stop advertising Loading", !io.github.nanomuse.avatar.AvatarStudio.generating)
                assertEquals("Candidate cancellation retains description", "Retained fixture description", io.github.nanomuse.avatar.AvatarStudio.description.value)
                assertEquals(io.github.nanomuse.avatar.AvatarStudio.Style.FLAT, io.github.nanomuse.avatar.AvatarStudio.style.value)
                assertTrue("Ready picture remains byte-identical", readyBytes.contentEquals(ready.single().file.readBytes()))
                val expectedLate = server.held.get()
                assertTrue("Actual image operation has a delayed response", expectedLate > 0)
                server.release.countDown()
                val lateDeadline = SystemClock.uptimeMillis() + 5000
                while (server.finished.get() < expectedLate && SystemClock.uptimeMillis() < lateDeadline) Thread.sleep(50)
                assertTrue("Bounded late responses finish", server.finished.get() >= expectedLate)
                settle()
                assertEquals("Late callbacks cannot replace cancelled tiles", 1, io.github.nanomuse.avatar.AvatarStudio.slots.value.count { it is io.github.nanomuse.avatar.AvatarStudio.Slot.Ready })
                assertTrue("Cancellation does not invent provider errors", io.github.nanomuse.avatar.AvatarStudio.slots.value.none { it is io.github.nanomuse.avatar.AvatarStudio.Slot.Failed })
                capture("avatar-cancelled-ready-input-retained")
            }
        } finally {
            server.release.countDown()
            val cleanupDeadline = SystemClock.uptimeMillis() + 10000
            while (io.github.nanomuse.avatar.AvatarStudio.generating && SystemClock.uptimeMillis() < cleanupDeadline) Thread.sleep(100)
            server.close()
            repo.deleteApiKey(providerId)
            repo.removeInstance(providerId)
            prefs.edit().apply { before.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            directory.listFiles().orEmpty().filter { it.name !in originalFiles }.forEach { it.delete() }
            originalFiles.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
            io.github.nanomuse.avatar.AvatarStudio.init(context)
            io.github.nanomuse.avatar.AvatarStudio.select(selectedBefore)
            assertTrue("Candidate operation never adopts or discards saved avatar", if (baseBefore == null) !baseFile.exists() else baseBefore.contentEquals(baseFile.readBytes()))
        }
    }

    /** Real OpenAI image responses: one completes, others wait; no external traffic or keys. */
    private class LocalImages : AutoCloseable {
        private val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.2"))
        val port = server.localPort
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicInteger(0)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        private val workers = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        private val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        private val response: ByteArray = run {
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val bytes = java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            image.recycle()
            org.json.JSONObject().put("data", org.json.JSONArray().put(org.json.JSONObject().put("b64_json", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))).toString().toByteArray()
        }
        private val acceptor = kotlin.concurrent.thread(isDaemon = true) {
            try { repeat(4) { number ->
                val socket = server.accept(); accepted.add(socket)
                workers.add(kotlin.concurrent.thread(isDaemon = true) {
                    try { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine(); check(request.contains("images/generations"))
                        var length = 0
                        while (true) { val line = reader.readLine(); if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                        var remaining = length
                        while (remaining > 0) { val count = reader.read(CharArray(remaining)); check(count > 0); remaining -= count }
                        started.countDown()
                        if (number > 0) { held.incrementAndGet(); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)) }
                        it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray() + response)
                        it.getOutputStream().flush()
                    } } catch (_: Exception) { /* Fixture cleanup closes held sockets. */ } finally { if (number > 0) finished.incrementAndGet() }
                })
            } } catch (_: java.io.IOException) { }
        }
        override fun close() { release.countDown(); server.close(); accepted.forEach { it.close() }; acceptor.join(1000); workers.forEach { it.join(1000) } }
    }

    @Test fun realCloudSuspendAndBlockingCallsPreserveSuccessErrorsCredentialAndOrigin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as MinisApp).providerRepository
        val prefs = context.getSharedPreferences("nanomuse", 0)
        val keys = listOf("cloud.base", "cloud.instance_id")
        val old = keys.associateWith { prefs.getString(it, null) }
        val id = "task3-cloud-${SystemClock.uptimeMillis()}"
        val server = ScriptedCloud(listOf(
            200 to "{}", 429 to "{\"error\":{\"code\":\"rate_limited\",\"message\":\"Fixture limit\",\"retry_after\":1.2,\"paused\":true}}",
            200 to "{\"rev\":7}", 503 to "unavailable", 200 to "{\"rev\":8}"
        ))
        try {
            val origin = "http://127.0.0.1:${server.port}"
            repo.addInstance(com.openminis.app.data.model.ProviderInstance(id, "Local cloud compatibility fixture",
                com.openminis.app.data.model.ProviderType.openAI, com.openminis.app.data.model.ProviderCredential.apiKey,
                customBaseURL = origin, appendV1Suffix = false))
            repo.saveApiKey(id, "task3-keyless-fixture-token")
            NanoMuseCloud.setBaseUrl(context, origin)
            prefs.edit().putString("cloud.instance_id", id).commit()
            kotlinx.coroutines.runBlocking { NanoMuseCloud.requestCode(context, "fixture") }
            val rate = try { kotlinx.coroutines.runBlocking { NanoMuseCloud.requestCode(context, "fixture") }; error("Expected actual non2xx") }
                catch (e: NanoMuseCloud.CloudException) { e }
            assertEquals(429, rate.status); assertEquals("rate_limited", rate.code); assertEquals(2, rate.retryAfterS); assertTrue(rate.paused)
            assertEquals("Blocking profile parsing remains compatible", 7, NanoMuseCloud.profile(context, withFace = false).getInt("rev"))
            val unavailable = try { NanoMuseCloud.profile(context, withFace = true); error("Expected actual HTTP status") }
                catch (e: NanoMuseCloud.CloudException) { e }
            assertEquals(503, unavailable.status); assertEquals("http_503", unavailable.code)
            assertEquals(8, NanoMuseCloud.putProfile(context, org.json.JSONObject().put("rev", 7)).getInt("rev"))
            assertTrue("All actual requests remain on the chosen bounded origin", server.requests.all { it.contains("host: 127.0.0.1:${server.port}", true) })
            assertTrue("Public code requests still send no credentials", server.requests.take(2).none { it.contains("authorization:", true) })
            assertTrue("Blocking profile calls retain existing bearer credential", server.requests.drop(2).all { it.contains("Authorization: Bearer task3-keyless-fixture-token", true) })
            assertTrue("Existing profile face=false endpoint is unchanged", server.requests[2].startsWith("GET /v1/me/profile?face=false "))
            assertTrue("Existing profile update endpoint is unchanged", server.requests[4].startsWith("PUT /v1/me/profile "))
        } finally {
            server.close(); repo.deleteApiKey(id); repo.removeInstance(id)
            prefs.edit().apply { old.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
        }
    }

    private class ScriptedCloud(private val replies: List<Pair<Int, String>>) : AutoCloseable {
        private val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { replies.forEach { (status, body) -> server.accept().use { socket ->
                socket.soTimeout = 10000
                val reader = socket.getInputStream().bufferedReader()
                val headers = StringBuilder(reader.readLine()).append('\n')
                var length = 0
                while (true) { val line = reader.readLine(); if (line.isEmpty()) break; headers.append(line).append('\n'); if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                var remaining = length
                while (remaining > 0) { val count = reader.read(CharArray(remaining)); check(count > 0); remaining -= count }
                requests.add(headers.toString())
                val bytes = body.toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                socket.getOutputStream().flush()
            } } } catch (_: java.io.IOException) { }
        }
        override fun close() { server.close(); worker.join(1000) }
    }

    @Test fun productionGalleryUsesNativeFontRtlControlsStatesAndReducedMotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val nativeLocale = args.getString("nativeLocale")
        val appearance = context.getSharedPreferences(com.openminis.app.ui.settings.PREF_APPEARANCE, 0)
        val languageKey = com.openminis.app.ui.settings.KEY_LANGUAGE
        val hadLanguage = appearance.contains(languageKey)
        val oldLanguage = appearance.getString(languageKey, null)
        val localeManager = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.LocaleManager::class.java) else null
        val oldLocales = localeManager?.applicationLocales
        if (nativeLocale != null) {
            appearance.edit().putString(languageKey, nativeLocale).commit()
            localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags(nativeLocale)
        }
        try {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                args.getString("expectedFontScale")?.let {
                    assertEquals("Actual native resource font scale", it.toFloat(), activity.resources.configuration.fontScale, 0.01f)
                }
                args.getString("expectedRtl")?.let {
                    assertEquals("Actual native resource layout direction", it.toBoolean(),
                        activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
                }
                activity.setContent { MinisTheme { DotComponentGallery() } }
            }
            settle()
            val more = requireNotNull(find(context.getString(R.string.nm_more)))
            assertTarget(more, "Gallery dialog action")
            touch(clickable(more), "Open actual M3 dialog")
            settle()
            val cancel = requireNotNull(find(context.getString(R.string.nm_cancel)))
            assertTarget(cancel, "Dialog Cancel")
            capture("gallery-dialog")
            touch(clickable(cancel), "Dismiss actual dialog")
            settle()
            val checkLabel = context.getString(R.string.nm_dot_components) + " · " + context.getString(R.string.nm_task_library)
            val check = requireNotNull(find(checkLabel))
            assertTarget(check, "M3 Checkbox")
            assertTrue("Native checkable state exists", check.isCheckable)
            val before = check.isChecked
            touch(clickable(check), "Toggle actual Checkbox")
            settle()
            assertEquals("Actual M3 checked state changes", !before, requireNotNull(find(checkLabel)).isChecked)
            capture("gallery-controls-native")
            val loading = requireNotNull(find(context.getString(R.string.nm_dot_loading)))
            assertTrue("Real specimen loading label visible", loading.isVisibleToUser)
            assertReadableNativeText(loading, "Loading")
            capture("gallery-loading-empty-error")
            if (args.getString("expectedReducedMotion") == "true") {
                assertEquals("Native animator preference disabled", 0f,
                    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f), 0f)
                val first = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                Thread.sleep(400)
                val second = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val margin = (32 * context.resources.displayMetrics.density).toInt()
                val a = Bitmap.createBitmap(first, 0, margin, first.width, first.height - margin * 2)
                val b = Bitmap.createBitmap(second, 0, margin, second.width, second.height - margin * 2)
                assertTrue("Reduced-motion actual loading region is static", a.sameAs(b))
                a.recycle(); b.recycle(); first.recycle(); second.recycle()
            }
            val retry = requireNotNull(find(context.getString(R.string.nm_avatar_retry)))
            assertTarget(retry, "Recoverable gallery error Retry")
            capture("gallery-ready-error-retry")
            val sheet = requireNotNull(find(context.getString(R.string.nm_task_library)))
            assertTarget(sheet, "Gallery sheet action")
            touch(clickable(sheet), "Open actual M3 sheet")
            settle()
            capture("gallery-sheet")
            assertNotNull("Sheet has actual dismiss action", find(context.getString(R.string.nm_cancel)))
        }
        } finally {
            if (nativeLocale != null) {
                appearance.edit().apply { if (hadLanguage) putString(languageKey, oldLanguage) else remove(languageKey) }.commit()
                if (oldLocales != null) localeManager?.applicationLocales = oldLocales
            }
        }
    }

    /** Native semantics cannot prove that the foreground is visible on the actual palette. */
    private fun assertReadableNativeText(node: AccessibilityNodeInfo, label: String) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val frame = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        fun luminance(pixel: Int): Double {
            fun channel(value: Int): Double {
                val x = value / 255.0
                return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(android.graphics.Color.red(pixel)) +
                0.7152 * channel(android.graphics.Color.green(pixel)) + 0.0722 * channel(android.graphics.Color.blue(pixel))
        }
        val background = luminance(frame.getPixel(bounds.left, bounds.top))
        var readablePixels = 0
        for (y in bounds.top until minOf(bounds.bottom, frame.height) step 2) {
            for (x in bounds.left until minOf(bounds.right, frame.width) step 2) {
                val foreground = luminance(frame.getPixel(x, y))
                val contrast = (maxOf(background, foreground) + 0.05) / (minOf(background, foreground) + 0.05)
                if (contrast >= 4.5) readablePixels++
            }
        }
        frame.recycle()
        assertTrue("$label has native glyph pixels with readable 4.5:1 contrast; count=$readablePixels", readablePixels >= 8)
    }

    private fun assertTarget(node: AccessibilityNodeInfo, label: String) {
        val bounds = Rect().also { clickable(node).getBoundsInScreen(it) }
        val min = (48 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
        assertTrue("$label native target is at least 48dp: $bounds", bounds.width() >= min && bounds.height() >= min)
    }

    private fun touch(node: AccessibilityNodeInfo, label: String) {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (eventType in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, now + eventType * 50L, eventType,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Real native touch injected: $label", automation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && !current.isSelected && current.parent != null) current = current.parent
        return current
    }

    private fun settle() {
        Thread.sleep(800)
        // Animating production avatars need bounded readiness rather than an unbounded Looper-idle wait.
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "dot-page-state").apply { mkdirs() }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        val log = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            log.append(" ".repeat(depth)).append("text=${node.text} desc=${node.contentDescription} selected=${node.isSelected} clickable=${node.isClickable} visible=${node.isVisibleToUser} bounds=$bounds\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it, 0) }
        File(output, "$name.txt").writeText(log.toString())
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        repeat(40) { attempt ->
            findImmediate(text)?.let { node ->
                val parent = clickable(node)
                // Progress/empty/error text has no action ancestor. Its own visible bounds
                // matter; climbing to the full-screen root invents an offscreen target.
                val target = if (parent.isClickable || parent.isSelected) parent else node
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                val display = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
                // A partly visible label is not proof that the native pointer target fits the viewport.
                if (bounds.top >= 24 * display.density && bounds.bottom <= display.heightPixels - 24 * display.density) return node
            }
            if (attempt % 4 == 3) {
                // Real accessibility scroll brings a page action into the native viewport.
                fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isScrollable && node.isVisibleToUser) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { return it } }
                    return null
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let { scrollable(it) }
                    ?.let { scroll -> if (!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            }
            Thread.sleep(250)
        }
        capture("missing-label-$text")
        return null
    }

    private fun findImmediate(text: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((node.text?.toString() == text || node.contentDescription?.toString() == text) && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child)?.let { return it }
            }
            return null
        }
        return visit(root)
    }
}
