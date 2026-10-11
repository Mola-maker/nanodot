package io.github.nanomuse.apiproof

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owned proof resources only. Reflection is confined to test source sets and verified by identity.
 * Request bytes remain the actual provider bytes. Response diagnostics are deliberately sanitized:
 * only fixed content-type, constant non-2xx body, bounded SSE lines, no credential echo.
 * This does not prove unmodified vendor error rendering or production transport policy.
 */
class ApiProofTransport(private val endpoint: HttpUrl, private val credential: String) : Closeable {
    private val admitted = AtomicBoolean(false)
    private val exchanges = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
    private val dispatcher = Dispatcher()
    val exchangeCount: Int get() = exchanges.get()
    val isClosed: Boolean get() = closed.get() && dispatcher.executorService.isShutdown && pool.connectionCount() == 0
    @Volatile var httpStatus = 0
        private set
    @Volatile var sawDone = false
        private set
    @Volatile var responseClosed = false
        private set

    @Volatile var responseReadStarted = false
        private set

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher).connectionPool(pool)
        .proxy(Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            // Network interceptor runs before CallServerInterceptor writes request headers/body.
            // It covers automatic follow-ups (including 503 Retry-After:0), separate Calls/collections.
            if (closed.get() || !admitted.compareAndSet(false, true)) throw IOException("Proof exchange already consumed")
            val request = chain.request()
            if (request.url != endpoint || request.method != "POST") throw IOException("Invalid proof destination")
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            validateBody(body)
            exchanges.incrementAndGet()
            try {
                val response = chain.proceed(request)
                httpStatus = response.code
                if (!response.isSuccessful) {
                    response.close()
                    responseClosed = true
                    response.newBuilder().headers(Headers.Builder().build())
                        .body("{\"error\":{\"message\":\"Proof HTTP failure\"}}".toResponseBody("application/json".toMediaType())).build()
                } else {
                    val original = response.body ?: throw IOException("Missing proof stream")
                    response.newBuilder()
                        .headers(Headers.Builder().add("Content-Type", "text/event-stream").build())
                        .body(sanitizedStream(original)).build()
                }
            } catch (_: IOException) {
                // Discard remote-controlled messages/cause chains; never print a raw network error.
                throw IOException("Proof transport interrupted")
            }
        }.build()

    fun provider(key: String, basePath: String): OpenAIProvider {
        check(!closed.get() && key == credential) { "Invalid proof credential handoff" }
        val provider = OpenAIProvider(
            apiKey = key, model = LLMModel("deepseek-flash", "DeepSeek Flash proof", "DeepSeek"),
            basePath = basePath, useResponsesAPI = false,
        )
        val field = OpenAIProvider::class.java.getDeclaredField("client")
        check(field.type == OkHttpClient::class.java) { "Unsupported proof transport seam" }
        field.isAccessible = true
        // Eager original references a shared pool: do not close, cancel, evict or mutate it.
        field.set(provider, client)
        check(field.get(provider) === client) { "Proof transport replacement failed" }
        provider.chatExtraBody = mapOf("thinking" to JSONObject().put("type", "disabled"), "max_tokens" to 64)
        return provider
    }

    fun flow(provider: OpenAIProvider) = provider.streamMessage(
        listOf(LLMMessage(LLMMessage.Role.USER, "Reply with the word READY.")),
        null, 64, null, emptyList(), emptyList(), ThinkingLevel.OFF,
    ).flowOn(Dispatchers.IO)

    private fun validateBody(serialized: String) {
        try {
            val body = JSONObject(serialized)
            check(body.getString("model") == "deepseek-flash" && body.getBoolean("stream"))
            check(body.getInt("max_tokens") == 64 && body.getInt("max_completion_tokens") == 64)
            check(body.getJSONObject("thinking").getString("type") == "disabled")
            check(!body.has("tools") && !body.has("temperature"))
            val messages = body.getJSONArray("messages")
            check(messages.length() == 1)
            val message = messages.getJSONObject(0)
            check(message.getString("role") == "user" && message.getString("content") == "Reply with the word READY.")
        } catch (_: Exception) { throw IOException("Invalid proof request body") }
    }

    /** Decode only data JSON before any line can reach the provider's raw/decoded diagnostics. */
    private fun validateSseData(line: String) {
        if (!line.startsWith("data:")) return // Comments/event/id/blank lines have no parsed JSON diagnostics.
        val payload = line.removePrefix("data:").let { if (it.startsWith(" ")) it.removePrefix(" ") else it }
        if (payload == "[DONE]") { sawDone = true; return }
        // Bound nesting BEFORE JSONObject/JSONTokener recursively allocate or parse.
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in payload) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= 32) }
                '}', ']' -> { depth--; check(depth >= 0) }
                '\'' -> error("Nonstandard proof JSON")
            }
        }
        check(!quoted && depth == 0)
        val parser = JSONTokener(payload)
        val parsed = parser.nextValue()
        check(parsed is JSONObject && parser.nextClean() == '\u0000')
        var inspected = 0
        fun inspect(value: Any?, nesting: Int) {
            check(nesting <= 32 && ++inspected <= 2048)
            when (value) {
                is String -> check(!value.contains(credential))
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        check(!key.contains(credential))
                        inspect(value.get(key), nesting + 1)
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) inspect(value.get(index), nesting + 1)
            }
        }
        inspect(parsed, 0)
    }

    private fun sanitizedStream(body: ResponseBody): ResponseBody = object : ResponseBody() {
        private val upstream = body.source()
        private val source = object : Source {
            private val pending = Buffer()
            private var totalBytes = 0L
            override fun timeout(): Timeout = upstream.timeout()
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0
                try {
                    if (pending.size == 0L) {
                        responseReadStarted = true
                        if (upstream.exhausted()) return -1
                        val line = upstream.readUtf8LineStrict(16_384)
                        if (line.contains(credential)) throw IOException("Credential echo rejected")
                        totalBytes += line.toByteArray(Charsets.UTF_8).size + 1
                        if (totalBytes > 262_144) throw IOException("Proof stream limit")
                        validateSseData(line)
                        pending.writeUtf8(line).writeByte(10)
                    }
                    return pending.read(sink, minOf(byteCount, pending.size))
                } catch (_: Exception) { throw IOException("Proof stream rejected") }
            }
            override fun close() { try { body.close() } finally { responseClosed = true } }
        }.buffer()
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = source
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            dispatcher.cancelAll()
            pool.evictAll()
            dispatcher.executorService.shutdownNow()
        }
    }
}
