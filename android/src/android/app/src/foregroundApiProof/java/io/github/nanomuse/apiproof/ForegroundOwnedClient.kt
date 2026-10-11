package io.github.nanomuse.apiproof

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual single-exchange boundary, before CallServer writes any request headers. */
class ForegroundOwnedClient(private val endpoint: HttpUrl, private val slot: ProofSlot,
    private val run: ForegroundProofContract, private val validate: (String) -> Unit,
    private val sanitize: (ResponseBody) -> ResponseBody) : Closeable {
    private val consumed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
    private val dispatcher = Dispatcher()
    private val count = AtomicInteger()
    val exchanges get() = count.get()
    @Volatile var httpStatus = 0; private set
    val isClosed get() = closed.get() && dispatcher.executorService.isShutdown && pool.connectionCount() == 0
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher).connectionPool(pool).proxy(Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
        .writeTimeout(10,TimeUnit.SECONDS).callTimeout(if(slot==ProofSlot.Chat) 90 else 40,TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            try {
                check(!closed.get() && consumed.compareAndSet(false,true))
                val request = chain.request()
                check(request.url == endpoint && request.method == "POST")
                validate(Buffer().also { request.body?.writeTo(it) }.readUtf8())
                run.begin(slot)
                count.incrementAndGet()
                val response = chain.proceed(request)
                httpStatus = response.code
                if (!response.isSuccessful) {
                    response.close()
                    response.newBuilder().headers(Headers.Builder().build()).body(
                        "{\"error\":{\"message\":\"Foreground proof HTTP failure\"}}".toResponseBody("application/json".toMediaType())).build()
                } else response.newBuilder().headers(Headers.Builder().add("Content-Type","text/event-stream").build())
                    .body(sanitize(checkNotNull(response.body))).build()
            } catch (_: Exception) {
                run.fail()
                throw IOException("Foreground proof transport refused")
            }
        }.build()
    override fun close() {
        if (closed.compareAndSet(false,true)) {
            dispatcher.cancelAll(); pool.evictAll(); dispatcher.executorService.shutdownNow()
        }
    }
}
