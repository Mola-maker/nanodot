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
    @Test(timeout = 15000) fun cancellingFactoryStreamDoesNotEmitFinished() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"index\":0}]}\n\n".repeat(100))
            .setBodyDelay(5, TimeUnit.SECONDS))
        val chunks = mutableListOf<LLMStreamChunk>()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            provider().streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64).collect {
                chunks.add(it)
                if (it is LLMStreamChunk.Started) started.complete(Unit)
            }
        }
        withTimeout(10000) { started.await() }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(chunks.none { it is LLMStreamChunk.Finished })
    }
}
