package io.github.nanomuse.apiproof

import com.openminis.app.data.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual production OpenAI provider against public loopback SSE, never a model endpoint. */
class ForegroundApiTransportTest {
    private fun gate():ForegroundProofContract {
        val run="01234567-89ab-cdef-0123-456789abcdef"
        return ForegroundProofContract(JSONObject("""{"schema":1,"run":"$run","owner":"$run","serial":"emulator-5584","source":"${"a".repeat(40)}","main_apk_sha256":"${"b".repeat(64)}","test_apk_sha256":"${"c".repeat(64)}","certificate_sha256":"${"d".repeat(64)}","reservations":[{"slot":"Chat","case":"foreground-chat-$run","number":5,"cap":64},{"slot":"CareInitial","case":"foreground-care-initial-$run","number":6,"cap":1024},{"slot":"CareNext","case":"foreground-care-next-$run","number":7,"cap":1024}]}"""))
    }
    private fun stream(transport:ForegroundApiTransport) = transport.provider.streamMessage(
        listOf(LLMMessage(LLMMessage.Role.USER,"Reply with the word READY.")),"Public system",64)
    private fun sse(content:String="READY") = "data: "+JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("index",0).put("delta",JSONObject().put("content",content)))).toString()+"\n\n"+
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\ndata: [DONE]\n\n"
    @Test fun genuineTextFinishUsageAndDoneReachConsumerOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse()))
            var checkpoints=0
            val proof=ForegroundApiTransport(ProofSlot.Chat,gate(),"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",checkpoint={ assertEquals("READY",it); checkpoints++ },endpoint=server.url("/chat/completions"))
            proof.use {
                val chunks=stream(proof).toList()
                assertEquals("READY",chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
                assertTrue(proof.sawDone && proof.sawText && proof.sawFinish && proof.sawUsage)
                assertEquals("stop",proof.finishReason)
                assertEquals(1,checkpoints); assertEquals(1,proof.exchanges)
                try { stream(proof).toList(); fail("duplicate collection admitted") } catch(error:LLMError.ProviderError) { assertEquals("Foreground proof ended",error.detail) }
                assertEquals(1,server.requestCount)
            }
            assertTrue(proof.isClosed)
        }
    }
    @Test fun titleAndForeignRequestNeverReachServer() = runBlocking {
        MockWebServer().use { server ->
            ForegroundApiTransport(ProofSlot.Chat,gate(),"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",endpoint=server.url("/chat/completions")).use { proof ->
                try { proof.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER,"title")),"Public system",64); fail("title admitted") } catch(_:LLMError.ProviderError) {}
                assertEquals(0,server.requestCount)
            }
        }
    }
    @Test fun threeOrderedActualProviderPathsEachUseOneExchangeAndClose() = runBlocking {
        MockWebServer().use { server ->
            listOf("READY","Public initial Focus instruction","Public next Focus instruction").forEach {
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse(it)))
            }
            val gate=gate(); val endpoint=server.url("/chat/completions")
            val chat=ForegroundApiTransport(ProofSlot.Chat,gate,"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",endpoint=endpoint)
            val initial=ForegroundApiTransport(ProofSlot.CareInitial,gate,"PUBLIC_SYNTHETIC_KEY","Public Care system","Public snapshot one",endpoint=endpoint)
            val next=ForegroundApiTransport(ProofSlot.CareNext,gate,"PUBLIC_SYNTHETIC_KEY","Public Care system","Public snapshot two",endpoint=endpoint)
            val proofs=listOf(chat,initial,next)
            try {
                stream(chat).toList(); gate.complete(ProofSlot.Chat)
                assertEquals("Public initial Focus instruction",initial.provider.sendMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER,"Public snapshot one")),"Public Care system",1024,0.0).text)
                gate.complete(ProofSlot.CareInitial)
                gate.authorizeNext(FocusLineage("old","fresh",1,2,true,"one","one",false,true,false,"Observable",1))
                assertEquals("Public next Focus instruction",next.provider.sendMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER,"Public snapshot two")),"Public Care system",1024,0.0).text)
                gate.complete(ProofSlot.CareNext)
                proofs.forEach { assertEquals(1,it.exchanges); assertEquals(200,it.httpStatus); assertEquals("stop",it.finishReason); assertTrue(it.bodyValidated && it.sawDone && it.sawUsage && it.responseClosed) }
                try { next.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER,"Public snapshot two")),"Public Care system",1024,0.0); fail("fourth exchange admitted") } catch(_:LLMError.ProviderError) {}
                assertEquals(3,server.requestCount)
            } finally { proofs.forEach { it.close() } }
            assertTrue(proofs.all { it.isClosed })
        }
    }
    @Test fun transientVendorErrorsBecomeFixedTerminalFailureWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0").setBody("private remote diagnostic"))
            ForegroundApiTransport(ProofSlot.Chat,gate(),"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",endpoint=server.url("/chat/completions")).use { proof ->
                try { stream(proof).toList(); fail("HTTP error passed") } catch(error:LLMError.ProviderError) { assertEquals("Foreground proof ended",error.detail); assertNull(error.cause) }
                assertEquals(1,server.requestCount); assertEquals(1,proof.exchanges)
            }
        }
    }
    @Test fun decodedCredentialEchoAndUnexpectedTextNeverReachConsumer() = runBlocking {
        listOf("PUBLIC_SYNTHETIC_KEY","Wrong text").forEach { text -> MockWebServer().use { server ->
            val payload=sse(text).replace("PUBLIC_SYNTHETIC_KEY","\\u0050UBLIC_SYNTHETIC_KEY")
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(payload))
            ForegroundApiTransport(ProofSlot.Chat,gate(),"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",endpoint=server.url("/chat/completions")).use { proof ->
                val accepted=mutableListOf<LLMStreamChunk>()
                try { stream(proof).collect { accepted+=it }; fail("untrusted output passed") } catch(_:LLMError.ProviderError) {}
                assertTrue(accepted.none { it is LLMStreamChunk.Text }); assertEquals(1,server.requestCount)
            }
        } }
    }
    @Test fun realConsumerCancellationIsPreservedAndCannotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse()))
            val reached=CompletableDeferred<Unit>()
            ForegroundApiTransport(ProofSlot.Chat,gate(),"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",
                checkpoint={ reached.complete(Unit); awaitCancellation() },endpoint=server.url("/chat/completions")).use { proof ->
                val consumer=launch { stream(proof).toList() }
                withTimeout(5000) { reached.await(); consumer.cancelAndJoin() }
                assertTrue(consumer.isCancelled)
                assertEquals(1,server.requestCount); assertEquals(1,proof.exchanges)
                try { stream(proof).toList(); fail("cancelled stream rearmed") } catch(_:LLMError.ProviderError) {}
                assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun returnedToolUseCannotReachTheCanonicalConsumer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"public\",\"type\":\"function\",\"function\":{\"name\":\"shell_execute\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"))
            ForegroundApiTransport(ProofSlot.Chat,gate(),"PUBLIC_SYNTHETIC_KEY","Public system","Reply with the word READY.",endpoint=server.url("/chat/completions")).use { proof ->
                val accepted=mutableListOf<LLMStreamChunk>()
                try { stream(proof).collect { accepted+=it }; fail("tool passed") } catch(_:LLMError.ProviderError) {}
                assertTrue(accepted.none { it is LLMStreamChunk.ToolUseStart || it is LLMStreamChunk.ToolInputDelta || it is LLMStreamChunk.ToolCallComplete })
                assertEquals(1,server.requestCount)
            }
        }
    }
}
