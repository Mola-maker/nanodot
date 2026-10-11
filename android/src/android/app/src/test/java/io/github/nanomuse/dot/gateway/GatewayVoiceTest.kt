package io.github.nanomuse.dot.gateway

import android.content.SharedPreferences
import com.openminis.app.MinisApp
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.voice.*
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual factory, HTTP transport and read-aloud lifecycle; audio is protocol-only. */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayVoiceTest {
    private lateinit var server: MockWebServer
    private val melo="@cf/myshell-ai/melotts"
    private val whisper="@cf/openai/whisper-large-v3-turbo"
    private val unsafeType=Class.forName("sun.misc.Unsafe")
    private val unsafe=unsafeType.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
    private fun allocate(type:Class<*>):Any=requireNotNull(unsafeType.getMethod("allocateInstance",Class::class.java).invoke(unsafe,type))
    private fun set(target:Any,field:String,value:Any?) {target.javaClass.getDeclaredField(field).apply {isAccessible=true}.set(target,value)}
    private fun get(target:Any,field:String):Any? = target.javaClass.getDeclaredField(field).apply {isAccessible=true}.get(target)
    private fun instance()=ProviderInstance("voice-local","Gateway",ProviderType.openAI,ProviderCredential.apiKey,customBaseURL=server.url("/v1").toString().trimEnd('/'))
    private fun provider()=VoiceProviderFactory.make(instance(),"local-protocol-fixture")!!
    @Before fun setup() {Dispatchers.setMain(UnconfinedTestDispatcher());server=MockWebServer();server.start()}
    @After fun cleanup() {server.shutdown();Dispatchers.resetMain();File("readaloud_tts.audio").delete()}
    @Test fun actualFactoryTranscriptionUsesWholeWavAndJson()=runBlocking {
        server.enqueue(MockResponse().setBody("{\"text\":\"你好\",\"language\":\"zh\",\"duration\":1}"))
        val wav=VoiceProvider.wrapPcm16InWav(ByteArray(32000),16000)
        val result=provider().transcribe(VoiceInputRequest(wav,whisper,"zh-CN"))
        assertEquals("你好",result.text);assertEquals("zh",result.language);assertEquals(1.0,result.durationSeconds)
        val req=server.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals("/v1/audio/transcriptions",req.path)
        assertEquals("Bearer local-protocol-fixture",req.getHeader("Authorization"))
        val body=req.body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(Regex("name=\"language\"[\\s\\S]*?\r\n\r\nzh\r\n").containsMatchIn(body));assertTrue(Regex("name=\"response_format\"[\\s\\S]*?\r\n\r\njson\r\n").containsMatchIn(body))
        assertTrue(body.contains(wav.toString(Charsets.ISO_8859_1)))
    }
    @Test fun successfulHttpErrorJsonCannotBecomeTranscription()=runBlocking {
        server.enqueue(MockResponse().setBody("{\"error\":{\"message\":\"unavailable\"}}"))
        val result=runCatching {provider().transcribe(VoiceInputRequest(ByteArray(44),whisper))}
        assertTrue("Error JSON must fail on accidental HTTP200",result.exceptionOrNull() is VoiceProviderException.Parse)
    }
    @Test fun actualFactoryMapsModelVoiceAndPreservesUnavailableSpeechError()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"Chinese cloud speech unverified\"}}"))
        val result=runCatching {provider().synthesize(VoiceOutputRequest("你好",melo,melo))}
        assertTrue(result.exceptionOrNull() is VoiceProviderException.Http)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Chinese cloud speech unverified"))
        val req=server.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals("/v1/audio/speech",req.path)
        val json=JSONObject(req.body.readUtf8());assertEquals(melo,json.getString("voice"));assertEquals("mp3",json.getString("response_format"));assertFalse(json.has("speed"))
    }
    @Test(timeout=15000) fun cancellingActualAsrAndTtsInterruptsBlockedOkHttp()=runBlocking {
        for(asr in listOf(true,false)) {
            server.enqueue(MockResponse().setBody(if(asr) "{\"text\":\"late\"}" else "late-audio").setBodyDelay(5,TimeUnit.SECONDS))
            var late=false
            val job=launch(Dispatchers.IO) {
                if(asr) provider().transcribe(VoiceInputRequest(ByteArray(44),whisper)) else provider().synthesize(VoiceOutputRequest("你好",melo,melo))
                late=true
            }
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));withTimeout(1500) {job.cancelAndJoin()}
            assertTrue(job.isCancelled);assertFalse(late)
        }
    }
    // Test-only framework allocation avoids Android init. Production provider,
    // repository credential read, stop/pause and playback dispatch remain real.
    private fun player(originMarker:String?=null):Pair<ReadAloudPlayer,ModelEntry> {
        val app=allocate(MinisApp::class.java) as MinisApp
        val repo=allocate(ProviderRepository::class.java) as ProviderRepository
        val entry=ModelEntry(providerInstanceId=instance().id,baseModel=LLMModel(melo,"Melo","Custom",outputModalities=listOf("audio")))
        val prefs=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) {_,method,args ->
            if(method.name=="getString") when(args!![0]) {
                "apikey_voice-local" -> "local-protocol-fixture"
                "nanodot_gateway_origin_voice-local" -> originMarker
                else -> null
            } else null
        } as SharedPreferences
        set(repo,"encryptedPrefs\$delegate",lazyOf(prefs))
        val routingPrefs=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) {_,method,_ -> if(method.name=="getString") entry.id else null} as SharedPreferences
        set(repo,"prefs",routingPrefs);set(repo,"_configLoaded",MutableStateFlow(true))
        set(repo,"_config",MutableStateFlow(ProviderConfig(instances=mutableListOf(instance()),modelEntries=mutableListOf(entry))))
        set(app,"providerRepository",repo);set(app,"subsystemsInitialized",true)
        val p=allocate(ReadAloudPlayer::class.java) as ReadAloudPlayer
        set(p,"appContext",app);set(p,"system",TextToSpeechManager());set(p,"scope",CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate))
        set(p,"queue",Channel<String>(Channel.UNLIMITED));set(p,"heldForCapture",mutableListOf<String>());set(p,"sentenceBuffer",StringBuilder())
        set(p,"pending",AtomicInteger());set(p,"_isSpeaking",MutableStateFlow(false))
        runCatching {val f=p.javaClass.getDeclaredField("generation").apply {isAccessible=true};f.set(p,f.type.getDeclaredConstructor().newInstance())}
        return p to entry
    }
    private suspend fun synthesizeInPlayer(p:ReadAloudPlayer,entry:ModelEntry) {
        val method=p.javaClass.declaredMethods.single {it.name=="speakViaProvider"}.apply {isAccessible=true}
        val args=mutableListOf<Any>(instance(),entry,"你好")
        if(method.parameterCount==5) {val g=get(p,"generation")!!;args.add(requireNotNull(g.javaClass.getMethod("ticket").invoke(g)))}
        suspendCancellableCoroutine<Unit> {cont ->
            val completion=object:kotlin.coroutines.Continuation<Boolean> {
                override val context=cont.context
                override fun resumeWith(result:Result<Boolean>) {result.fold({cont.resume(Unit) { _,_,_ -> }},{cont.resumeWith(Result.failure(it))})}
            }
            args.add(completion)
            try {val result=method.invoke(p,*args.toTypedArray());if(result!==kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) cont.resume(Unit) { _,_,_ -> }}
            catch(e:java.lang.reflect.InvocationTargetException) {cont.resumeWith(Result.failure(e.cause!!))}
        }
    }
    @Test(timeout=5000) fun boundProjectCredentialCannotReachLoopbackReadAloudProvider()=runBlocking {
        val (p,entry)=player("https://molamaker.si")
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"unverified\"}}"))
        try {
            synthesizeInPlayer(p,entry)
            assertNull("Origin-bound project credential must not dispatch loopback speech",server.takeRequest(200,TimeUnit.MILLISECONDS))
        } finally {p.shutdown()}
    }
    @Test(timeout=15000) fun realReadAloudStopAndPauseCancelInFlightSynthesis()=runBlocking {
        for(pause in listOf(false,true)) {
            val (p,entry)=player();server.enqueue(MockResponse().setBody("late-audio").setBodyDelay(5,TimeUnit.SECONDS))
            val job=launch(Dispatchers.IO) {synthesizeInPlayer(p,entry)};runCatching {set(p,"utteranceJob",job)}
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));if(pause) p.suspendForCapture() else p.stop()
            withTimeout(1500) {job.join()};assertTrue("Stop/pause must cancel synthesis",job.isCancelled)
            assertFalse(File("readaloud_tts.audio").exists());p.shutdown()
        }
    }
    @Test(timeout=15000) fun realReadAloudGenerationDiscardsLateBytes()=runBlocking {
        val (p,entry)=player();server.enqueue(MockResponse().setBody("synthetic-protocol-bytes").setBodyDelay(700,TimeUnit.MILLISECONDS))
        val result=async(Dispatchers.IO) {runCatching {synthesizeInPlayer(p,entry)}}
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));p.stop()
        assertTrue("Superseded generation must propagate cancellation",withTimeout(4000) {result.await()}.exceptionOrNull() is CancellationException)
        assertFalse("Late bytes must never reach playback cache",File("readaloud_tts.audio").exists());p.shutdown()
    }
    private suspend fun invokeSuspend(p:ReadAloudPlayer,name:String,args:List<Any> = emptyList()) {
        val method=p.javaClass.declaredMethods.single {it.name==name}.apply {isAccessible=true}
        suspendCancellableCoroutine<Unit> {cont ->
            val completion=object:kotlin.coroutines.Continuation<Any?> {
                override val context=cont.context
                override fun resumeWith(result:Result<Any?>) {result.fold({cont.resume(Unit) {_,_,_->}},{cont.resumeWith(Result.failure(it))})}
            }
            try {val value=method.invoke(p,*(args+completion).toTypedArray());if(value!==kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) cont.resume(Unit) {_,_,_->}}
            catch(e:java.lang.reflect.InvocationTargetException) {cont.resumeWith(Result.failure(e.cause!!))}
        }
    }
    @Test(timeout=15000) fun actualQueueWorkerSurvivesStopAndServesNextUtterance()=runBlocking {
        val (p,_)=player()
        @Suppress("UNCHECKED_CAST") val queue=get(p,"queue") as Channel<String>
        val pending=get(p,"pending") as AtomicInteger
        server.enqueue(MockResponse().setBody("late-protocol-audio").setBodyDelay(5,TimeUnit.SECONDS))
        val worker=launch(Dispatchers.Main.immediate) {invokeSuspend(p,"consumeQueue")};set(p,"worker",worker)
        pending.incrementAndGet();queue.send("first")
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));p.stop()
        assertTrue(worker.isActive)
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"unverified\"}}"))
        pending.incrementAndGet();queue.send("second")
        val next=server.takeRequest(2,TimeUnit.SECONDS)!!
        assertEquals("second",JSONObject(next.body.readUtf8()).getString("input"));assertTrue(worker.isActive)
        p.shutdown();withTimeout(1500) {worker.join()}
    }
    @Test(timeout=15000) fun cancelledGenerationNeverStartsSystemFallback()=runBlocking {
        val (p,_)=player();val g=get(p,"generation")!!
        val ticket=requireNotNull(g.javaClass.getMethod("ticket").invoke(g))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"unverified\"}}").setBodyDelay(700,TimeUnit.MILLISECONDS))
        val outcome=async(Dispatchers.IO) {runCatching {invokeSuspend(p,"speakOne",listOf("你好",ticket))}}
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));p.stop()
        assertTrue("Stopped generation cannot awaitReady/fallback",withTimeout(1500) {outcome.await()}.exceptionOrNull() is CancellationException)
        p.shutdown()
    }

}
