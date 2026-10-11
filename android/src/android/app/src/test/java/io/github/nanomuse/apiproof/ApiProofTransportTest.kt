package io.github.nanomuse.apiproof

import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Public synthetic requests only; removal of the exchange gate must break duplicate collection. */
class ApiProofTransportTest {
    private val publicSse = """
        data: {"id":"proof","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"READY"},"finish_reason":null}],"usage":null}

        data: {"id":"proof","object":"chat.completion.chunk","created":1,"model":"deepseek-flash","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6}}

        data: [DONE]

    """.trimIndent() + "\n\n"

    private fun sse() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(publicSse)

    @Test fun `current final choice usage SSE and documented overrides reach actual provider`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse())
            server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                val provider = transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))
                val chunks = transport.flow(provider).toList()
                assertEquals("READY", chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
                assertEquals(listOf("stop"), chunks.filterIsInstance<LLMStreamChunk.Finished>().map { it.stopReason })
                val usage = chunks.filterIsInstance<LLMStreamChunk.Usage>().single().usage
                assertEquals(6, usage.inputTokens)
                assertEquals(4, usage.cacheReadInputTokens)
                assertEquals(10, usage.latestContextTokens)
                assertEquals(2, usage.outputTokens)
                assertEquals(1, transport.exchangeCount)
                assertTrue(transport.sawDone)
                assertEquals(200, transport.httpStatus)
                assertTrue(chunks.none { it is LLMStreamChunk.ThinkingDelta || it is LLMStreamChunk.ReasoningContent || it is LLMStreamChunk.ToolUseStart || it is LLMStreamChunk.MediaAttachment })
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                val body = JSONObject(request.body.readUtf8())
                assertEquals("/chat/completions", request.path)
                assertEquals("deepseek-flash", body.getString("model"))
                assertEquals(64, body.getInt("max_tokens"))
                assertEquals(64, body.getInt("max_completion_tokens"))
                assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
                assertFalse(body.has("tools"))
                assertEquals(1, body.getJSONArray("messages").length())
                assertEquals("Reply with the word READY.", body.getJSONArray("messages").getJSONObject(0).getString("content"))
                assertTrue(body.getBoolean("stream"))
            }
        }
    }

    @Test fun `second collection is rejected before a second wire request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse()); server.enqueue(sse()); server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                val flow = transport.flow(transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/')))
                flow.toList()
                assertTrue(runCatching { flow.toList() }.isFailure)
                assertEquals(1, server.requestCount)
                assertEquals(1, transport.exchangeCount)
            }
        }
    }

    @Test fun `redirect does not follow Location or expose raw error body`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/follow"))
                .setHeader("x-request-id", "synthetic-proof-key").setBody("synthetic-proof-key"))
            server.enqueue(sse())
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                assertTrue(runCatching { transport.flow(transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))).toList() }.isFailure)
                assertEquals(1, server.requestCount)
                assertEquals(307, transport.httpStatus)
            }
        }
    }

    @Test fun `retryable server response ends after one exchange`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")); server.enqueue(sse()); server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                assertTrue(runCatching { transport.flow(transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))).toList() }.isFailure)
                assertEquals(1, server.requestCount)
                assertEquals(1, transport.exchangeCount)
            }
        }
    }

    @Test fun `disconnect after request does not retry`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.enqueue(sse()); server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                assertTrue(runCatching { transport.flow(transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))).toList() }.isFailure)
                assertEquals(1, server.requestCount)
                assertEquals(1, transport.exchangeCount)
            }
        }
    }

    @Test fun `credential echo never reaches provider SSE logs or chunks`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(publicSse.replace("READY", "synthetic-proof-key")))
            server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                val chunks = mutableListOf<LLMStreamChunk>()
                val failure = runCatching {
                    transport.flow(transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))).collect { chunks.add(it) }
                }.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(chunks.filterIsInstance<LLMStreamChunk.Text>().any { "synthetic-proof-key" in it.text })
                assertFalse(transport.sawDone)
                assertTrue(transport.responseClosed)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `owned transport strips response diagnostic headers and error bytes`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setHeader("x-request-id", "synthetic-proof-key").setBody("synthetic-proof-key"))
            server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                val request = Request.Builder().url(server.url("/chat/completions"))
                    .post("""{"model":"deepseek-flash","stream":true,"max_tokens":64,"max_completion_tokens":64,"thinking":{"type":"disabled"},"messages":[{"role":"user","content":"Reply with the word READY."}]}""".toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer synthetic-proof-key").build()
                val field = ApiProofTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
                (field.get(transport) as OkHttpClient).newCall(request).execute().use { response ->
                    assertEquals(400, response.code)
                    assertTrue(response.headers.names().isEmpty())
                    assertEquals("{\"error\":{\"message\":\"Proof HTTP failure\"}}", response.body!!.string())
                }
                assertTrue(transport.responseClosed)
            }
        }
    }

    @Test fun `finish at EOF alone is not observed DONE`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(publicSse.replace("data: [DONE]", "")))
            server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                val chunks = transport.flow(transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))).toList()
                assertEquals(1, chunks.filterIsInstance<LLMStreamChunk.Finished>().size)
                assertFalse(transport.sawDone)
                assertTrue(transport.responseClosed)
            }
        }
    }

    @Test fun `timeout cancels blocked reader then closes only owned resources`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("").setHeader("Content-Length", "1000"))
            server.start()
            val transport = ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key")
            try {
                val provider = transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))
                val start = System.nanoTime()
                val pending = async { withTimeout(1500) { transport.flow(provider).toList() } }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(1, TimeUnit.SECONDS) })
                withTimeout(1000) { while (!transport.responseReadStarted) delay(10) }
                assertEquals(200, transport.httpStatus)
                assertFalse(transport.responseClosed)
                val failure = runCatching { pending.await() }.exceptionOrNull()
                assertTrue(failure is TimeoutCancellationException)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4000)
                assertTrue(transport.responseClosed)
                assertEquals(1, transport.exchangeCount)
            } finally { transport.close() }
            assertTrue(transport.isClosed)
        }
    }

    @Test fun `invalid configured cap is rejected before request bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse()); server.start()
            ApiProofTransport(server.url("/chat/completions"), "synthetic-proof-key").use { transport ->
                val provider = transport.provider("synthetic-proof-key", server.url("/").toString().trimEnd('/'))
                provider.chatExtraBody = mapOf("max_tokens" to 1000)
                assertTrue(runCatching { transport.flow(provider).toList() }.isFailure)
                assertEquals(0, transport.exchangeCount)
                assertEquals(0, server.requestCount)
            }
        }
    }
}
