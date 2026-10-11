package io.github.nanomuse.apiproof

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import io.github.nanomuse.dot.care.CarePlanResult
import io.github.nanomuse.dot.care.CarePlanner
import io.github.nanomuse.dot.care.CareProposalValidator
import io.github.nanomuse.dot.screen.NodeDto
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.SceneKey
import io.github.nanomuse.dot.screen.ScreenSnapshot
import io.github.nanomuse.dot.screen.ScreenTransform
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Root invokes this method explicitly. No input means skip before any provider/client exists. */
class ManualCarePlannerApiProofTest {
    @Test fun currentPublicNodeSingleReservedAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var outcome = "invalid-input"
        var inputPresent = false
        var transport: CareApiProofTransport? = null
        var calls = 0
        var usage: LLMUsage? = null
        var schemaValid = false
        var currentNode = false
        var closed = true
        try {
            val credential = PrivateProofInput.consume(context)
            if (credential == null) outcome = "skipped"
            assumeTrue("Manual Care input absent; no provider constructed", credential != null)
            inputPresent = true
            outcome = "setup-failed"
            val owned = CareApiProofTransport(credential!!)
            transport = owned
            closed = false
            val actualProvider = owned.provider()
            // Observes the actual Care call, then delegates unchanged to the production provider.
            // Implement the boundary explicitly: Kotlin `by` would also delegate sendMessage
            // and bypass this observer's sendMessageClamped override.
            val observed = object : LLMProvider {
                override val name get() = actualProvider.name
                override var model: LLMModel
                    get() = actualProvider.model
                    set(value) { actualProvider.model = value }
                override val defaultMaxOutputTokens get() = actualProvider.defaultMaxOutputTokens
                override fun effectiveMaxOutputTokens(model: LLMModel) = actualProvider.effectiveMaxOutputTokens(model)
                override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?,
                    maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
                    tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): LLMResponse {
                    check(++calls == 1 && maxTokens == CareApiProofTransport.CAP && temperature == 0.0)
                    check(tools.isEmpty() && imageParts.isEmpty() && thinkingLevel == ThinkingLevel.OFF)
                    check(messages.size == 1 && messages.single().role == LLMMessage.Role.USER)
                    owned.expectedSystem = systemPrompt
                    owned.expectedContext = messages.single().content
                    return actualProvider.sendMessage(messages, systemPrompt, maxTokens, temperature,
                        imageParts, tools, thinkingLevel).also { usage = it.usage }
                }
                override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?,
                    maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
                    tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> =
                    error("Care must use one materialized provider call")
            }
            outcome = "failed"
            publicSnapshot().use { snapshot ->
                val result = runBlocking {
                    withTimeout(35_000) {
                        CarePlanner(observed, "Please describe the current screen.").next(
                            "Open the Help page using the current Help button.", snapshot, emptyList())
                    }
                }
                if (result is CarePlanResult.Step) {
                    schemaValid = CareProposalValidator().validate(result.proposal, snapshot)
                    currentNode = result.proposal.snapshotId == snapshot.id && result.proposal.scene == snapshot.scene &&
                        snapshot.nodes.singleOrNull { it.ref == result.proposal.targetRef }?.let {
                            it.enabled && !it.password && it.bounds == RectPx(24f, 100f, 264f, 164f)
                        } == true
                }
                check(result is CarePlanResult.Step && schemaValid && currentNode)
                check(calls == 1 && owned.exchangeCount == 1 && owned.bodyValidated && owned.httpStatus == 200 && owned.sawDone)
                check(owned.responseClosed)
                check(usage?.let { it.inputTokens >= 0 && it.latestContextTokens >= 0 &&
                    it.outputTokens in 1..CareApiProofTransport.CAP } != false)
                outcome = "passed"
            }
        } catch (assumption: org.junit.AssumptionViolatedException) {
            throw assumption
        } catch (_: TimeoutCancellationException) {
            outcome = "timeout"
        } catch (_: Throwable) {
            // Never publish arbitrary response, instruction, exception, request, credential or stack.
        } finally {
            transport?.let {
                try { it.close(); closed = it.isClosed } catch (_: Throwable) { closed = false }
            }
            if (outcome == "passed" && !closed) outcome = "cleanup-failed"
            if (inputPresent || outcome == "invalid-input") {
                val safeUsage = usage?.takeIf { it.inputTokens >= 0 && it.latestContextTokens >= 0 &&
                    it.outputTokens in 0..CareApiProofTransport.CAP && (it.cacheReadInputTokens ?: 0) >= 0 }
                writeResult(context.cacheDir, JSONObject().apply {
                    put("fixture", "public-help-button-v1")
                    put("endpoint", "https://api.deepseek.com/chat/completions")
                    put("model", "deepseek-flash")
                    put("outcome", outcome)
                    put("http", transport?.httpStatus ?: 0)
                    put("schema", schemaValid); put("currentnode", currentNode)
                    put("modelrequests", calls); put("exchanges", transport?.exchangeCount ?: 0)
                    put("bodyvalidated", transport?.bodyValidated ?: false)
                    put("done", transport?.sawDone ?: false); put("ownedclosed", closed)
                    put("responseclosed", transport?.responseClosed ?: false)
                    put("cap", CareApiProofTransport.CAP); put("thinking", "disabled")
                    put("tools", 0); put("actions", 0)
                    put("usage", safeUsage?.let { u -> JSONObject().put("freshinput", u.inputTokens)
                        .put("cachedinput", u.cacheReadInputTokens ?: 0).put("prompt", u.latestContextTokens)
                        .put("output", u.outputTokens).put("total", u.latestContextTokens.toLong() + u.outputTokens) } ?: JSONObject.NULL)
                })
            }
        }
        if (outcome != "passed") throw AssertionError("Manual Care proof failed; inspect sanitized cache result")
    }

    private fun publicSnapshot(): ScreenSnapshot {
        val bounds = RectPx(0f, 0f, 360f, 640f)
        return ScreenSnapshot("care-public-current-v1", 1, 1,
            SceneKey("io.github.nanomuse.publicfixture", 7, 0, 1), bounds, 0,
            RectPx(0f, 0f, 0f, 0f), null,
            listOf(NodeDto("help-current", RectPx(24f, 100f, 264f, 164f), "Help", "Open Help page",
                "public_help_button", "button", false, true, false, true, false)),
            null, ScreenTransform(bounds, 360, 640))
    }

    private fun writeResult(cache: File, result: JSONObject) {
        val temporary = File.createTempFile("nanodot-care-proof-result-", ".tmp", cache)
        try {
            Os.chmod(temporary.path, 384)
            FileOutputStream(temporary).use { stream ->
                stream.write(result.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Os.rename(temporary.path, File(cache, RESULT_NAME).path)
        } finally { temporary.delete() }
    }

    companion object { const val RESULT_NAME = "nanodot-care-proof.result.json" }
}
