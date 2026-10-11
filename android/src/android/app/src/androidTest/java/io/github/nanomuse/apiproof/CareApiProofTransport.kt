package io.github.nanomuse.apiproof

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Test-only owned client. Actual provider request bytes are checked, never rewritten. */
internal class CareApiProofTransport(private val credential: String) : Closeable {
    private val endpoint = "https://api.deepseek.com/chat/completions".toHttpUrl()
    private val sanitizer = ApiProofTransport(endpoint, credential)
    private val admitted = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val exchanges = AtomicInteger()
    private val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
    private val dispatcher = Dispatcher()
    val exchangeCount get() = exchanges.get()
    val sawDone get() = sanitizer.sawDone
    val responseClosed get() = sanitizer.responseClosed
    val isClosed get() = closed.get() && sanitizer.isClosed &&
        dispatcher.executorService.isShutdown && pool.connectionCount() == 0
    @Volatile var httpStatus = 0
        private set
    @Volatile var bodyValidated = false
        private set
    var expectedSystem: String? = null
    var expectedContext: String? = null

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher).connectionPool(pool).proxy(Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (closed.get() || !admitted.compareAndSet(false, true)) throw IOException("Care exchange consumed")
            val request = chain.request()
            if (request.url != endpoint || request.method != "POST") throw IOException("Care destination rejected")
            validateBody(Buffer().also { request.body?.writeTo(it) }.readUtf8())
            exchanges.incrementAndGet()
            try {
                val response = chain.proceed(request)
                httpStatus = response.code
                if (!response.isSuccessful) {
                    response.close()
                    response.newBuilder().headers(Headers.Builder().build())
                        .body("{\"error\":{\"message\":\"Care HTTP failure\"}}".toResponseBody("application/json".toMediaType())).build()
                } else {
                    val original = response.body ?: throw IOException("Care stream absent")
                    response.newBuilder().headers(Headers.Builder().add("Content-Type", "text/event-stream").build())
                        .body(sanitizer.sanitizeResponse(original)).build()
                }
            } catch (_: IOException) { throw IOException("Care transport interrupted") }
        }.build()

    fun provider(): OpenAIProvider = OpenAIProvider(
        apiKey = credential,
        model = LLMModel("deepseek-flash", "DeepSeek Flash Care proof", "DeepSeek", maxOutputTokens = CAP),
        basePath = "https://api.deepseek.com", useResponsesAPI = false,
    ).apply {
        val field = OpenAIProvider::class.java.getDeclaredField("client")
        check(field.type == OkHttpClient::class.java) { "Care transport seam unavailable" }
        field.isAccessible = true
        field.set(this, client)
        check(field.get(this) === client) { "Care owned client differs" }
        chatExtraBody = mapOf("thinking" to JSONObject().put("type", "disabled"), "max_tokens" to CAP)
    }

    private fun validateBody(serialized: String) {
        try {
            val body = JSONObject(serialized)
            check(body.getString("model") == "deepseek-flash" && body.getBoolean("stream"))
            check(body.getInt("max_tokens") == CAP && body.getInt("max_completion_tokens") == CAP)
            check(body.getDouble("temperature") == 0.0)
            check(body.getJSONObject("thinking").getString("type") == "disabled")
            check(!body.has("tools") && !body.has("tool_choice"))
            val messages = body.getJSONArray("messages")
            check(messages.length() == 2)
            check(expectedSystem != null && expectedContext != null)
            val context = JSONObject(expectedContext!!)
            check(context.getString("snapshotId") == "care-public-current-v1")
            check(context.getJSONObject("scene").getString("packageName") == "io.github.nanomuse.publicfixture")
            check(context.getJSONArray("nodes").length() == 1 &&
                context.getJSONArray("nodes").getJSONObject(0).getString("ref") == "help-current")
            check(context.getJSONArray("verified").length() == 0)
            for ((index, role, content) in listOf(Triple(0, "system", expectedSystem), Triple(1, "user", expectedContext))) {
                val message = messages.getJSONObject(index)
                check(message.length() == 2 && message.getString("role") == role && message.getString("content") == content)
            }
            bodyValidated = true
        } catch (_: Exception) { throw IOException("Care request rejected") }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { dispatcher.cancelAll(); pool.evictAll(); dispatcher.executorService.shutdownNow() }
            finally { sanitizer.close() }
        }
    }

    companion object { const val CAP = 1024 }
}
