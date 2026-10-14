package io.github.nanomuse.dot.gateway

import com.openminis.app.data.model.*
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.openai.OpenAIProvider
import okhttp3.OkHttpClient
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The fixtures are also asserted by local-workerd gateway tests; real production client path. */
class GatewayProtocolTest {
    private lateinit var server: MockWebServer
    private val qwen = "@cf/qwen/qwen3-30b-a3b-fp8"
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }
    private fun projectFile(path: String): File {
        var dir: File? = File(requireNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val file = File(dir, path)
            if (file.isFile) return file
            dir = dir.parentFile
        }
        error("Project file missing: $path")
    }
    private fun fixture(name: String) = projectFile("gateway/test/fixtures/$name").readText()
    // Test-only transport: production factory/serializer sees the exact HTTPS
    // origin, while OkHttp sends the already-built request to the local fixture.
    private fun localTransport(provider: LLMProvider): LLMProvider {
        val field = OpenAIProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(provider) as OkHttpClient
        field.set(provider, client.newBuilder().addInterceptor { chain ->
            val original = chain.request()
            chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
                .newBuilder().request(original).build()
        }.build())
        return provider
    }
    // Run the real bundled parser and enrichment without an Android Context or
    // a network refresh. Restore its process cache so other provider tests keep
    // their own setup and no production seam is needed.
    private fun bundledEnrichment(models: List<LLMModel>): List<LLMModel> {
        val type = ModelsDevApi::class.java
        val parse = type.getDeclaredMethod("parseRegistry", String::class.java).apply { isAccessible = true }
        val cache = type.getDeclaredField("cachedRegistry").apply { isAccessible = true }
        val timestamp = type.getDeclaredField("cacheTimestamp").apply { isAccessible = true }
        val previous = cache.get(ModelsDevApi); val oldTime = timestamp.getLong(ModelsDevApi)
        try {
            cache.set(ModelsDevApi, parse.invoke(ModelsDevApi, projectFile("android/src/android/app/src/main/assets/models-dev-api.json").readText()))
            timestamp.setLong(ModelsDevApi, System.currentTimeMillis())
            return ModelsDevApi.enrichModels(models)
        } finally { cache.set(ModelsDevApi, previous); timestamp.setLong(ModelsDevApi, oldTime) }
    }
    private fun provider() = ProviderFactory.create(
        ProviderInstance("gateway-protocol", "Gateway", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = server.url("/v1").toString().trimEnd('/')),
        "local-protocol-fixture", LLMModel(qwen, "Qwen", "Custom"),
    )
    private fun stream(body: String): List<LLMStreamChunk> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        return runBlocking { provider().streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "你好")), null, 64).toList() }
    }
    @Test fun exactOriginPolicyAcceptsOnlyNanodotHttpsOrigin() {
        for (base in listOf("https://molamaker.si", "https://molamaker.si/", "https://molamaker.si/v1", "https://molamaker.si/v1/", "https://molamaker.si:443/v1", "HTTPS://MOLAMAKER.SI/v1")) {
            assertTrue(base, GatewayHistoryPolicy.forbidsReasoningHistory(base))
        }
        for (base in listOf("http://molamaker.si/v1", "https://molamaker.si:444/v1", "https://molamaker.si.evil.example/v1", "https://evil-molamaker.si/v1", "https://example.com/molamaker.si", "https://molamaker.si@example.com/v1", "https://example.com?v=molamaker.si", "not a URL")) {
            assertFalse(base, GatewayHistoryPolicy.forbidsReasoningHistory(base))
        }
    }
    @Test fun catalogueUsesRealOpenAIModelsApi() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("models.json")))
        val models = OpenAIModelsApi.fetchModels("local-protocol-fixture", server.url("/v1").toString().trimEnd('/'))
        assertEquals(3, models.size)
        assertEquals(listOf("text"), models[0].inputModalities)
        assertEquals(listOf("text", "image"), models[1].inputModalities)
        assertEquals(listOf("audio"), models[2].inputModalities)
        assertEquals(listOf("text"), models[2].outputModalities)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer local-protocol-fixture", request.getHeader("Authorization"))
    }
    @Test fun factoryStreamReadsSharedChineseReasoningToolsUsageFixture() {
        val events = JSONObject(fixture("stream.json")).getJSONArray("events")
        val body = (0 until events.length()).joinToString("") { "data: ${events.getJSONObject(it)}\n\n" } + "data: [DONE]\n\n"
        val chunks = stream(body)
        assertEquals("你好，世界", chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
        assertEquals("思考", chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text })
        val tool = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("call_1", tool.id); assertEquals("lookup", tool.name); assertEquals("中文", tool.args.getString("value"))
        assertEquals(3, chunks.filterIsInstance<LLMStreamChunk.Usage>().last().usage.outputTokens)
        assertEquals("tool_calls", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        assertEquals(qwen, JSONObject(request.body.readUtf8()).getString("model"))
    }
    @Test fun ordinaryTwoTurnHistoryAfterReasoningUsesVerifiedMessageEncoding() = runBlocking {
        val events = JSONObject(fixture("normal-stream.json")).getJSONArray("events")
        val body = (0 until events.length()).joinToString("") { "data: ${events.getJSONObject(it)}\n\n" } + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(fixture("models.json")))
        val models = OpenAIModelsApi.fetchModels("local-protocol-fixture", server.url("/v1").toString().trimEnd('/'))
        server.takeRequest(2, TimeUnit.SECONDS)
        for (model in bundledEnrichment(models).take(2)) {
            assertEquals(true, model.supportsReasoning)
            val instance = ProviderInstance("gateway-history", "Gateway", ProviderType.openAI, ProviderCredential.apiKey,
                customBaseURL = "https://molamaker.si/v1")
            val actualProvider = localTransport(ProviderFactory.create(instance, "local-protocol-fixture", model))
            val firstMessages = listOf(LLMMessage(LLMMessage.Role.USER, "你好"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            val chunks = actualProvider.streamMessage(firstMessages, null, 64, thinkingLevel = ThinkingLevel.OFF).toList()
            server.takeRequest(2, TimeUnit.SECONDS)
            val answer = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
            val reasoning = chunks.filterIsInstance<LLMStreamChunk.ReasoningContent>().single().content
            assertEquals("思考", reasoning)
            val history = firstMessages + LLMMessage(LLMMessage.Role.ASSISTANT, answer, reasoningContent = reasoning) +
                LLMMessage(LLMMessage.Role.USER, "继续")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            actualProvider.streamMessage(history, null, 64, thinkingLevel = ThinkingLevel.OFF).toList()
            val requestBody = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(JSONObject(fixture("history.json")).getJSONArray("messages").toString(), requestBody.getJSONArray("messages").toString())
            assertFalse(requestBody.getJSONArray("messages").getJSONObject(1).has("reasoning_content"))
        }
    }
    @Test fun otherOriginsKeepExistingReasoningHistory() = runBlocking {
        val model = bundledEnrichment(listOf(LLMModel(qwen, "Qwen", "Custom"))).single()
        assertEquals(true, model.supportsReasoning)
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "first"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "answer", reasoningContent = "captured"),
            LLMMessage(LLMMessage.Role.USER, "next"))
        for (base in listOf("https://example.com/v1", "https://molamaker.si.evil.example/v1", "https://molamaker.si:444/v1", "http://molamaker.si/v1")) {
            val instance = ProviderInstance("legacy-history", "Legacy", ProviderType.openAI, ProviderCredential.apiKey, customBaseURL = base)
            val actualProvider = localTransport(ProviderFactory.create(instance, "local-protocol-fixture", model))
            val events = JSONObject(fixture("normal-stream.json")).getJSONArray("events")
            val wire = (0 until events.length()).joinToString("") { "data: ${events.getJSONObject(it)}\n\n" } + "data: [DONE]\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(wire))
            actualProvider.sendMessage(history, null, 64, thinkingLevel = ThinkingLevel.OFF)
            val serialized = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("captured", serialized.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
        }
    }
    @Test fun emptyToolCallsErrorAndGenuineToolOnlyStreamUseRealFactory() {
        val shared = JSONObject(fixture("tool-output-validation.json"))
        fun wire(key: String, done: Boolean): String {
            val events = shared.getJSONArray(key)
            return (0 until events.length()).joinToString("") { "data: ${events.getJSONObject(it)}\n\n" } +
                if (done) "data: [DONE]\n\n" else ""
        }
        val empty = runCatching { stream(wire("empty_normalized_events", false)) }
        assertTrue("The normalized empty-tool error must fail the real provider", empty.isFailure)
        assertTrue(empty.exceptionOrNull() is CancellationException)
        val causes = generateSequence(empty.exceptionOrNull()) { it.cause }.take(8).toList()
        assertTrue(causes.joinToString(" -> "), causes.any { it is LLMError.ProviderError &&
            it.message.orEmpty().contains("Upstream stream failed or ended without completion.") })
        val chunks = stream(wire("tool_only_events", true))
        assertTrue(chunks.none { it is LLMStreamChunk.Text })
        val call = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("call_tool_only", call.id)
        assertEquals("lookup", call.name)
        assertEquals("中文", call.args.getString("value"))
        assertEquals("tool_calls", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
    }
    @Test fun stopAndLengthRemainDistinct() {
        for (reason in listOf("stop", "length")) {
            val chunks = stream("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\ndata: [DONE]\n\n")
            assertEquals(reason, chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
        }
    }
    @Test fun httpAndStreamErrorsAreFailures() {
        for (status in listOf(401, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "60").setBody("{\"error\":{\"message\":\"Gateway refused request\"}}"))
            val result = runCatching { runBlocking { provider().streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64).toList() } }
            assertTrue("HTTP $status must fail", result.isFailure)
            if (status == 401) assertTrue(result.exceptionOrNull() is LLMError.InvalidApiKey)
            if (status == 429) assertTrue(result.exceptionOrNull() is LLMError.RateLimited)
        }
        for (body in listOf("", "data: {\"error\":{\"code\":\"upstream_stream_error\",\"message\":\"Upstream stream failed\"}}\n\n")) {
            assertTrue(runCatching { stream(body) }.isFailure)
        }
    }
    /** Loopback response headers reach the real provider; its body remains explicitly held. */
    private class HeldStream : AutoCloseable {
        private val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val headersSent = java.util.concurrent.CountDownLatch(1)
        private val release = java.util.concurrent.CountDownLatch(1)
        @Volatile private var connection: java.net.Socket? = null
        @Volatile private var closing = false
        @Volatile private var failure: Throwable? = null
        private val worker = kotlin.concurrent.thread(name = "gateway-held-stream", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    connection = socket
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val headers = java.io.ByteArrayOutputStream()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && headers.size() < 64_000)
                        headers.write(next)
                        tail = (tail + next.toChar()).takeLast(4)
                    }
                    val text = headers.toString("UTF-8")
                    check(text.lineSequence().first().startsWith("POST /v1/chat/completions "))
                    var remaining = Regex("(?im)^Content-Length: (\\d+)").find(text)
                        ?.groupValues?.get(1)?.toInt() ?: 0
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size))
                        check(count > 0)
                        remaining -= count
                    }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                            "Content-Length: 1\r\nConnection: close\r\n\r\n").toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                val expectedClose = error is java.net.SocketException && closing &&
                    (listener.isClosed || connection?.isClosed == true)
                if (!expectedClose) failure = error
            }
        }
        override fun close() {
            closing = true
            release.countDown()
            val errors = mutableListOf<Throwable>()
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) { errors.add(error) }
            }
            // Aggregate errors so one failed close cannot skip the other close or bounded join.
            attempt { connection?.close() }
            attempt { listener.close() }
            attempt { worker.join(1000) }
            if (worker.isAlive) errors.add(AssertionError("Held response worker must exit within bounded cleanup"))
            failure?.let { errors.add(it) }
            if (errors.isNotEmpty()) {
                val first = errors.first()
                errors.drop(1).forEach { if (it !== first) first.addSuppressed(it) }
                throw first
            }
        }
    }

    @Test(timeout = 15000) fun cancellingFactoryStreamDoesNotEmitFinished() = runBlocking {
        val held = HeldStream()
        val actualProvider = ProviderFactory.create(
            ProviderInstance("gateway-protocol", "Gateway", ProviderType.openAI, ProviderCredential.apiKey,
                customBaseURL = "http://127.0.0.1:${held.port}/v1"),
            "local-protocol-fixture", LLMModel(qwen, "Qwen", "Custom"),
        )
        val chunks = mutableListOf<LLMStreamChunk>()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            actualProvider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64).collect {
                chunks.add(it)
                if (it is LLMStreamChunk.Started) started.complete(Unit)
            }
        }
        try {
            withTimeout(10000) { started.await() }
            assertTrue("Provider received the actual held response headers", held.headersSent.await(1, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(chunks.none { it is LLMStreamChunk.Finished })
        } finally {
            // Release the held body even if cancellation assertions fail, then close/join the fixture.
            try { held.close() } finally {
                withContext(NonCancellable) { withTimeout(3000) { job.cancelAndJoin() } }
            }
        }
    }
}
