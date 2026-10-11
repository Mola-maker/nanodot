package io.github.nanomuse.apiproof

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Explicit root-owned manual case. Absent input skips before provider construction.
 * One streamMessage Flow, one collection, no retry/fallback/session/preferences/DAO.
 * This test is not evidence that a real API request or native execution succeeded.
 */
class ManualDeepSeekApiProofTest {
    @Test fun directProviderSingleReservedAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var status = "invalid-input"
        var httpStatus = 0
        var exchanges = 0
        var deltas = 0
        var finishes = 0
        var finish: String? = null
        var usage: LLMUsage? = null
        var sawDone = false
        var closed = true
        val visible = StringBuilder()
        var inputPresent = false
        try {
            val credential = PrivateProofInput.consume(context)
            if (credential == null) status = "skipped"
            assumeTrue("Manual proof input absent; no provider was constructed", credential != null)
            inputPresent = true
            status = "setup-failed"
            val transport = ApiProofTransport("https://api.deepseek.com/chat/completions".toHttpUrl(), credential!!)
            closed = false
            try {
                val provider = transport.provider(credential, "https://api.deepseek.com")
                status = "failed"
                runBlocking {
                    withTimeout(45_000) {
                        // Deliberately no toList/resubscription/retry operator and exactly one collect.
                        transport.flow(provider).collect { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.Text -> {
                                    check(visible.length + chunk.text.length <= 4096) { "Proof text limit" }
                                    if (chunk.text.isNotEmpty()) deltas++
                                    visible.append(chunk.text)
                                }
                                is LLMStreamChunk.Usage -> { check(usage == null); usage = chunk.usage }
                                is LLMStreamChunk.Finished -> { finishes++; finish = chunk.stopReason }
                                is LLMStreamChunk.Started -> Unit
                                is LLMStreamChunk.ThinkingDelta -> check(chunk.text.isEmpty())
                                is LLMStreamChunk.ReasoningContent -> check(chunk.content.isEmpty())
                                else -> error("Unexpected proof chunk")
                            }
                        }
                    }
                }
                check(transport.httpStatus == 200 && transport.exchangeCount == 1 && transport.sawDone)
                check(visible.isNotBlank() && finishes == 1 && finish in setOf("stop", "length"))
                check(usage != null && usage!!.outputTokens in 1..64 && usage!!.inputTokens >= 0 && usage!!.latestContextTokens >= 0)
                status = "passed"
            } finally {
                httpStatus = transport.httpStatus
                exchanges = transport.exchangeCount
                sawDone = transport.sawDone
                transport.close()
                closed = transport.isClosed
                if (status == "passed" && !closed) status = "cleanup-failed"
            }
        } catch (e: org.junit.AssumptionViolatedException) {
            throw e
        } catch (_: TimeoutCancellationException) {
            status = "timeout"
        } catch (_: Throwable) {
            if (status == "passed") status = "cleanup-failed"
            // No raw exception, stack, request, headers, response body, or credential output.
        } finally {
            if (inputPresent || status == "invalid-input") {
                val safeFinish = finish?.takeIf { it in setOf("stop", "length") }
                val result = JSONObject().apply {
                    put("endpoint", "https://api.deepseek.com/chat/completions")
                    put("model", "deepseek-flash")
                    put("status", JSONObject().put("outcome", status).put("http", httpStatus).put("done", sawDone).put("ownedclosed", closed))
                    put("configuredcap", JSONObject().put("max_tokens", 64).put("max_completion_tokens", 64).put("thinking", "disabled"))
                    put("exchangecount", exchanges)
                    put("deltacount", deltas)
                    put("finish", safeFinish ?: JSONObject.NULL)
                    put("usage", usage?.let { u -> JSONObject().put("freshinput", u.inputTokens).put("cachedinput", u.cacheReadInputTokens ?: 0).put("prompt", u.latestContextTokens).put("output", u.outputTokens).put("total", u.latestContextTokens.toLong() + u.outputTokens) } ?: JSONObject.NULL)
                    // Avoid saving arbitrary vendor response text. Only the requested public word is allowlisted.
                    put("text", if (visible.toString().trim() == "READY") "READY" else "[omitted]")
                }
                writeResult(context.cacheDir, result)
            }
        }
        if (status != "passed") throw AssertionError("Manual provider proof failed; inspect sanitized cache result")
    }

    private fun writeResult(cache: File, result: JSONObject) {
        val temporary = File.createTempFile("nanodot-api-proof-result-", ".tmp", cache)
        try {
            Os.chmod(temporary.path, 384)
            FileOutputStream(temporary).use { stream ->
                stream.write(result.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Same-directory rename atomically replaces a stale result, without following its symlink.
            Os.rename(temporary.path, File(cache, PrivateProofInput.RESULT_NAME).path)
        } finally { temporary.delete() }
    }
}
