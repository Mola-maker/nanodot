package io.github.nanomuse.dot.gateway

import android.content.SharedPreferences
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.voice.*
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Actual repository persistence, catalogue and model refresh; only Android framework/DAO IO is in memory. */
class GatewayConnectionTest {
    private val values = mutableMapOf<String,Any?>()
    private val secrets = mutableMapOf<String,Any?>()
    private val ownership = mutableMapOf<String,Any?>()
    private val rows = mutableMapOf<String,List<Any>>()
    private fun prefs(data:MutableMap<String,Any?>):SharedPreferences {
        lateinit var editor:SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { _,method,args ->
            when(method.name) {
                "putString","putLong","putBoolean" -> { data[args!![0] as String]=args[1]; editor }
                "remove" -> {data.remove(args!![0]);editor}
                "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args ->
            when(method.name) {"edit" -> editor;"getString","getLong","getBoolean" -> data[args!![0]] ?: args[1];else -> null}
        } as SharedPreferences
    }
    private fun staticClient(field:java.lang.reflect.Field,value:OkHttpClient) {
        val type=Class.forName("sun.misc.Unsafe")
        val unsafe=type.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
        val base=type.getMethod("staticFieldBase",java.lang.reflect.Field::class.java).invoke(unsafe,field)
        val offset=type.getMethod("staticFieldOffset",java.lang.reflect.Field::class.java).invoke(unsafe,field) as Long
        type.getMethod("putObject",Any::class.java,Long::class.javaPrimitiveType,Any::class.java).invoke(unsafe,base,offset,value)
    }
    private fun set(target:Any,name:String,value:Any?) {target.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(target,value)}
    private fun repo(config:ProviderConfig):ProviderRepository {
        val u=Class.forName("sun.misc.Unsafe");val unsafe=u.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
        val repo=u.getMethod("allocateInstance",Class::class.java).invoke(unsafe,ProviderRepository::class.java) as ProviderRepository
        val flow=MutableStateFlow(config);val loaded=MutableStateFlow(true)
        set(repo,"_config",flow);set(repo,"config",flow.asStateFlow());set(repo,"_configLoaded",loaded);set(repo,"configLoaded",loaded.asStateFlow())
        set(repo,"configLock",Any());set(repo,"configLoadComplete",CompletableDeferred(Unit))
        set(repo,"json",Json {ignoreUnknownKeys=true;encodeDefaults=true;coerceInputValues=true})
        set(repo,"prefs",prefs(values));set(repo,"encryptedPrefs\$delegate",lazyOf(prefs(secrets)))
        val dao=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(ProviderConfigDao::class.java)) { _,method,args ->
            when(method.name) {
                "replaceAll" -> { for((i,name) in listOf("loadInstances","loadEntries","loadGroups","loadAgentLoopIds","loadMeta").withIndex()) {
                    @Suppress("UNCHECKED_CAST") val list=args!![i] as List<Any>; rows[name]=list
                }; Unit }
                "instanceCount" -> rows["loadInstances"]?.size ?: 0
                else -> rows[method.name] ?: emptyList<Any>()
            }
        } as ProviderConfigDao
        set(repo,"providerDao",dao)
        return repo
    }
    private suspend fun connect(repo:ProviderRepository,key:String) {
        val type=runCatching {Class.forName("io.github.nanomuse.dot.gateway.GatewayConnection")}.getOrNull()
        assertNotNull("Gateway connection must use the loaded ProviderRepository",type)
        val target=type!!.getConstructor(ProviderRepository::class.java,SharedPreferences::class.java).newInstance(repo,prefs(ownership))
        val method=type.methods.single {it.name=="connect"}
        suspendCoroutine<Unit> {cont ->
            try {if(method.invoke(target,key,cont)!==COROUTINE_SUSPENDED) cont.resume(Unit)}
            catch(e:java.lang.reflect.InvocationTargetException) {cont.resumeWith(Result.failure(e.cause!!))}
        }
    }
    @Test fun realRepositoryPreservesOriginsCredentialsModelsGroupsAndReconnectOwnership()=runBlocking {
        val server=MockWebServer();server.start()
        val apiType=OpenAIModelsApi::class.java;val clientField=apiType.getDeclaredField("client").apply {isAccessible=true}
        val original=clientField.get(OpenAIModelsApi) as OkHttpClient
        val cache=ModelsDevApi::class.java.getDeclaredField("cachedRegistry").apply {isAccessible=true}
        val timestamp=ModelsDevApi::class.java.getDeclaredField("cacheTimestamp").apply {isAccessible=true}
        val oldCache=cache.get(ModelsDevApi);val oldTime=timestamp.getLong(ModelsDevApi)
        cache.set(ModelsDevApi,emptyMap<String,Any>());timestamp.setLong(ModelsDevApi,System.currentTimeMillis())
        staticClient(clientField,original.newBuilder().addInterceptor {chain ->
            val request=chain.request();assertEquals("https://molamaker.si/v1/models",request.url.toString())
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build()).newBuilder().request(request).build()
        }.build())
        try {
            val old=ProviderInstance("old-custom","Existing",ProviderType.openAI,ProviderCredential.apiKey,customBaseURL="https://relay.invalid",appendV1Suffix=true)
            val entry=ModelEntry(uuid="old-custom/old-text",providerInstanceId=old.id,baseModel=LLMModel("old-text","Old","Custom"))
            val group=ModelGroup(name="Keep",memberEntryIds=mutableListOf(entry.id))
            val repo=repo(ProviderConfig(instances=mutableListOf(old),modelEntries=mutableListOf(entry),modelGroups=mutableListOf(group),defaultPrimaryGroupId=group.id,defaultSubGroupId=group.id,visionGroupId=group.id,voiceInputGroupId=group.id,voiceOutputGroupId=group.id))
            val oldKey=UUID.randomUUID().toString();val key=UUID.randomUUID().toString();repo.saveApiKey(old.id,oldKey)
            server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"@cf/qwen/qwen3-30b-a3b-fp8\",\"input_modalities\":[\"text\"],\"output_modalities\":[\"text\"]}]}"))
            connect(repo,key)
            val gateway=repo.instances.single {it.id!=old.id}
            assertNotNull(UUID.fromString(gateway.id));assertEquals("https://molamaker.si",gateway.customBaseURL)
            assertTrue(gateway.appendV1Suffix);assertFalse(gateway.azureMode);assertFalse(gateway.useResponsesAPI)
            assertEquals(old,repo.instance(old.id));assertEquals(oldKey,repo.loadApiKey(old.id));assertEquals(key,repo.loadApiKey(gateway.id))
            assertEquals(group.memberEntryIds,repo.config.value.modelGroups.single().memberEntryIds)
            assertEquals(group.id,repo.config.value.defaultPrimaryGroupId)
            assertEquals(group.id,repo.config.value.defaultSubGroupId)
            assertEquals(group.id,repo.config.value.visionGroupId)
            assertEquals(group.id,repo.config.value.voiceInputGroupId)
            assertEquals(group.id,repo.config.value.voiceOutputGroupId)
            assertTrue(repo.config.value.modelEntries.any {it.baseModel.id=="old-text"})
            assertFalse(values["config"].toString().contains(key));assertFalse(values["config"].toString().contains(oldKey))
            val before=repo.config.value.modelEntries.map {it.baseModel.id}
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"refused\"}}"))
            connect(repo,key)
            assertEquals(before,repo.config.value.modelEntries.map {it.baseModel.id})
            val restarted=repo(ProviderConfig())
            val load=ProviderRepository::class.java.getDeclaredMethod("loadConfig").apply {isAccessible=true}
            set(restarted,"_config",MutableStateFlow(load.invoke(restarted) as ProviderConfig))
            // Public state flow follows the authoritative actual load, as constructor init does.
            val field=ProviderRepository::class.java.getDeclaredField("_config").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val loaded=field.get(restarted) as MutableStateFlow<ProviderConfig>
            set(restarted,"config",loaded.asStateFlow())
            server.enqueue(MockResponse().setResponseCode(401))
            connect(restarted,key)
            assertEquals(2,restarted.instances.size);assertEquals(gateway.id,restarted.instances.single {it.id!=old.id}.id)
        } finally {staticClient(clientField,original);cache.set(ModelsDevApi,oldCache);timestamp.setLong(ModelsDevApi,oldTime);server.shutdown()}
    }
    @Test fun actualChatModelsAsrAndTtsComposersUseOneVersionForAllBaseForms()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            for(form in listOf("", "/", "/v1", "/v1/")) {
                val instance=ProviderInstance(UUID.randomUUID().toString(),"Local",ProviderType.openAI,ProviderCredential.apiKey,
                    customBaseURL=server.url("/").toString().trimEnd('/')+form,appendV1Suffix=true)
                val key=UUID.randomUUID().toString()
                server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"text\"}]}"))
                OpenAIModelsApi.fetchModels(key,instance.effectiveBaseURL)
                assertEquals("/v1/models",server.takeRequest().path)
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"fixture\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"))
                ProviderFactory.create(instance,key,LLMModel("text","Text","Custom")).streamMessage(listOf(LLMMessage(LLMMessage.Role.USER,"fixture")),null,32).toList()
                assertEquals("/v1/chat/completions",server.takeRequest().path)
                val voice=VoiceProviderFactory.make(instance,key)!!
                server.enqueue(MockResponse().setBody("{\"text\":\"fixture\"}"))
                voice.transcribe(VoiceInputRequest(ByteArray(44),"@cf/openai/whisper-large-v3-turbo"))
                assertEquals("/v1/audio/transcriptions",server.takeRequest().path)
                server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"unverified\"}}"))
                assertTrue(runCatching {voice.synthesize(VoiceOutputRequest("fixture","@cf/myshell-ai/melotts"))}.isFailure)
                assertEquals("/v1/audio/speech",server.takeRequest().path)
            }
        } finally {server.shutdown()}
    }

}
