package io.github.nanomuse.apiproof

import com.openminis.app.data.model.*
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Temporary real-provider binding. It never persists a key or ordinary model selection. */
internal class ForegroundApiTransport(private val slot: ProofSlot, private val run: ForegroundProofContract,
    private val credential: String, private val expectedSystem: String, private val expectedUser: String,
    expectedTools: List<AgentToolDefinition> = emptyList(), private val checkpoint: suspend (String) -> Unit = {},
    endpoint: HttpUrl = "https://api.deepseek.com/chat/completions".toHttpUrl()) : Closeable {
    private val tools = expectedTools.toList()
    private val wireTools = JSONArray(tools.map { it.toOpenAIJson() })
    // Immutable, hash-provenance copy of the previously reviewed bounded decoded-echo sanitizer.
    private val sanitizer = ApiProofTransport(endpoint,credential)
    private val owned = ForegroundOwnedClient(endpoint,slot,run,{ body ->
        ForegroundWireGuard.validate(body,slot.cap,expectedSystem,expectedUser,wireTools,
            if(slot==ProofSlot.Chat) null else 0.0)
        bodyValidated=true
    },sanitizer::sanitizeResponse)
    private val invoked = AtomicBoolean()
    @Volatile var bodyValidated = false; private set
    private val delegate = OpenAIProvider(apiKey=credential,
        model=LLMModel("deepseek-flash","Foreground public proof","DeepSeek",maxOutputTokens=slot.cap,inputModalities=listOf("text")),
        basePath=endpoint.newBuilder().encodedPath("/").build().toString().removeSuffix("/"),useResponsesAPI=false).apply {
        val field=OpenAIProvider::class.java.getDeclaredField("client")
        check(field.type==OkHttpClient::class.java); field.isAccessible=true
        field.set(this,owned.client); check(field.get(this)===owned.client)
        chatExtraBody=mapOf("thinking" to JSONObject().put("type","disabled"),"max_tokens" to slot.cap)
    }
    val exchanges get() = owned.exchanges
    val proofSlot get() = slot
    val httpStatus get() = owned.httpStatus
    val sawDone get() = sanitizer.sawDone
    val responseClosed get() = sanitizer.responseClosed
    val isClosed get() = owned.isClosed && sanitizer.isClosed
    @Volatile var sawText = false; private set
    @Volatile var sawUsage = false; private set
    @Volatile var sawFinish = false; private set
    @Volatile var finishReason: String? = null; private set
    @Volatile var deltas = 0; private set
    @Volatile var usage: LLMUsage? = null; private set
    private fun terminal(): Nothing { run.fail(); throw LLMError.ProviderError("Foreground proof ended") }
    private fun boundary(messages: List<LLMMessage>,systemPrompt: String?,maxTokens: Int,temperature: Double?,
        images: List<LLMMessage.ImagePart>,definitions: List<AgentToolDefinition>,thinking: ThinkingLevel) {
        check(systemPrompt==expectedSystem && maxTokens==slot.cap && images.isEmpty() && thinking==ThinkingLevel.OFF)
        check(temperature == if(slot==ProofSlot.Chat) null else 0.0)
        check(messages.size==1 && messages.single().let { m ->
            m.role==LLMMessage.Role.USER && m.content==expectedUser && m.imageParts.isEmpty() && m.audioParts.isEmpty() &&
                m.reasoningContent==null && m.contentParts.all { p -> p is AgentContentPart.Text && p.text==expectedUser }
        })
        check(sameJson(JSONArray(definitions.map { it.toOpenAIJson() }),wireTools))
    }
    val provider: LLMProvider = object : LLMProvider {
        override val name = "Foreground temporary DeepSeek proof"
        override var model = delegate.model
        override val streamTextIsMonolithic get() = delegate.streamTextIsMonolithic
        override suspend fun sendMessageClamped(messages: List<LLMMessage>,systemPrompt:String?,maxTokens:Int,
            temperature:Double?,imageParts:List<LLMMessage.ImagePart>,tools:List<AgentToolDefinition>,thinkingLevel:ThinkingLevel): LLMResponse {
            if(slot==ProofSlot.Chat) terminal() // title, summary and compaction cannot spend a slot.
            try {
                check(invoked.compareAndSet(false,true)); boundary(messages,systemPrompt,maxTokens,temperature,imageParts,tools,thinkingLevel)
                val result=delegate.sendMessage(messages,systemPrompt,maxTokens,temperature,imageParts,tools,thinkingLevel)
                check(result.mediaAttachments.isEmpty() && result.stopReason=="stop" && result.text.isNotBlank())
                sawText=true; sawUsage=result.usage!=null; usage=result.usage; sawFinish=true; finishReason=result.stopReason
                return result
            } catch(cancelled: CancellationException) {
                run.fail(); currentCoroutineContext().ensureActive(); terminal()
            }
            catch(_: Exception) { terminal() }
        }
        override fun streamMessageClamped(messages:List<LLMMessage>,systemPrompt:String?,maxTokens:Int,
            temperature:Double?,imageParts:List<LLMMessage.ImagePart>,tools:List<AgentToolDefinition>,thinkingLevel:ThinkingLevel) = flow {
            try {
                check(slot==ProofSlot.Chat && invoked.compareAndSet(false,true))
                boundary(messages,systemPrompt,maxTokens,temperature,imageParts,tools,thinkingLevel)
                val text=StringBuilder(); var held=false
                delegate.streamMessage(messages,systemPrompt,maxTokens,temperature,imageParts,tools,thinkingLevel).collect { chunk ->
                    when(chunk) {
                        is LLMStreamChunk.Text -> {
                            text.append(chunk.text); check(text.length<=32)
                            val public=text.toString().trim()
                            check("READY".startsWith(public))
                            if(chunk.text.isNotEmpty()) { sawText=true; deltas++ }
                            emit(chunk)
                            if(!held && public.isNotEmpty()) { held=true; checkpoint(public) }
                        }
                        is LLMStreamChunk.Started -> emit(chunk)
                        is LLMStreamChunk.Usage -> { sawUsage=true; usage=chunk.usage; emit(chunk) }
                        is LLMStreamChunk.Finished -> { check(chunk.stopReason=="stop"); sawFinish=true; finishReason=chunk.stopReason; emit(chunk) }
                        else -> terminal() // no tool/reasoning/media can reach the canonical consumer.
                    }
                }
                check(text.toString().trim()=="READY" && sawText && sawUsage && sawFinish && sawDone)
            } catch(cancelled: CancellationException) {
                // The production channel may cancel itself with a wrapped provider/network error.
                // Preserve cancellation only when the actual consumer job was cancelled.
                run.fail(); currentCoroutineContext().ensureActive(); terminal()
            }
            catch(_: Exception) { terminal() }
        }.flowOn(Dispatchers.IO)
    }
    override fun close() { try { owned.close() } finally { sanitizer.close() } }
}
