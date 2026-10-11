package io.github.nanomuse.apiproof

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.*
import com.openminis.app.tools.AgentTools
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import io.github.nanomuse.dot.care.*
import io.github.nanomuse.dot.chat.DotChatAdapter
import io.github.nanomuse.dot.gesture.DotIntent
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** One manual real-API case; absent fresh owner input skips before UI or database changes. */
@RunWith(AndroidJUnit4::class)
class ManualForegroundApiProofTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as MinisApp
    private lateinit var automation: UiAutomation
    private lateinit var owner: String
    private lateinit var source: String
    private var ownerDirectory: File?=null
    private var sequence=0
    private fun field(value: Any,name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(value)
    private fun set(value: Any,name: String,replacement: Any?) {
        value.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(value,replacement)
        check(field(value,name) === replacement)
    }
    private fun stage(name: String, extra: Bundle = Bundle()) {
        val fields=JSONObject()
        extra.keySet().forEach { key ->
            val value=extra.get(key)
            check(value is String || value is Boolean || value is Int || value is Float)
            fields.put(key,if(key=="slotResults") JSONArray(value as String) else value)
        }
        val info=app.packageManager.getPackageInfo(app.packageName,0)
        val receipt=JSONObject().put("schema",1).put("owner",owner).put("source",source).put("stage",name)
            .put("sequence",sequence+1).put("targetPid",Process.myPid()).put("targetLastUpdateTime",info.lastUpdateTime).put("fields",fields)
        ownerDirectory?.let { root ->
            writePublic(File(root,"foreground-status.json"),receipt)
            if(name=="case_finally") writePublic(File(root,"foreground-result.json"),receipt)
        }
        inst.sendStatus(2,Bundle(extra).apply {
            putString("foregroundStage",name); putString("foregroundOwner",owner)
            putString("foregroundSourceClaim",source); putInt("foregroundSequence",++sequence)
        })
    }
    private fun writePublic(path:File,body:JSONObject) {
        check(path.parentFile==ownerDirectory && body.toString().toByteArray().size<=32768)
        val temporary=File(path.parentFile,path.name+".tmp")
        check(!temporary.exists() && !java.nio.file.Files.isSymbolicLink(path.toPath()))
        FileOutputStream(temporary).use { stream -> Os.chmod(temporary.path,384); stream.write(body.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        java.nio.file.Files.move(temporary.toPath(),path.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun journal(id:String,title:String,rows:JSONArray=JSONArray(),complete:Boolean=false) = JSONObject()
        .put("schema",1).put("owner",owner).put("source",source).put("session_id",id).put("title",title)
        .put("model","deepseek-flash").put("memory_enabled",0).put("cleanup_state",if(complete) "complete" else "pending")
        .put("allowed_user",REQUEST).put("allowed_assistant","READY").put("owned_rows",rows)
    private fun recordRows(id:String,title:String) = runBlocking {
        val rows=app.chatRepository.dao.loadMessages(id)
        check(rows.size<=2)
        val recorded=JSONArray()
        rows.forEach { row ->
            val parts=JSONArray(row.partsJson)
            check(parts.length()==1 && parts.getJSONObject(0).let { part -> part.keys().asSequence().toSet()==setOf("type","value") &&
                part.getString("type")=="text" && ((row.role=="user" && part.getString("value")==REQUEST) ||
                (row.role=="assistant" && part.getString("value").trim()=="READY")) })
            recorded.put(JSONObject().put("id",row.id).put("role",row.role).put("parts_json",row.partsJson))
        }
        writePublic(File(ownerDirectory,"foreground-journal.json"),journal(id,title,recorded))
        recorded
    }
    private fun awaitStreamRelease(expectedSequence:Int,operation:String="release-chat-stream",timeout:Long=60000) {
        check(operation in setOf("release-chat-stream","release-preflight","release-care-next") && timeout in 1..60000)
        val path=File(ownerDirectory,"foreground-ui-control.json")
        val deadline=SystemClock.uptimeMillis()+timeout
        while(!path.exists() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50)
        check(path.exists()) { "Public stream release was not acknowledged" }
        val before=Os.lstat(path.path)
        check(OsConstants.S_ISREG(before.st_mode) && before.st_mode and 4095==384 && before.st_uid==Process.myUid() && before.st_nlink==1L && before.st_size in 1..1024)
        val fd=Os.open(path.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
        java.io.FileInputStream(fd).use { stream ->
            val opened=Os.fstat(fd); check(opened.st_ino==before.st_ino && opened.st_dev==before.st_dev && opened.st_size==before.st_size)
            val body=JSONObject(stream.readBytes().toString(Charsets.UTF_8))
            body.exact("schema","owner","source","sequence","operation")
            check(body.getInt("schema")==1 && body.getString("owner")==owner && body.getString("source")==source &&
                body.getInt("sequence")==expectedSequence && body.getString("operation")==operation)
            check(Os.lstat(path.path).st_ino==opened.st_ino); Os.remove(path.path)
            check(!path.exists() && Os.fstat(fd).st_nlink==0L)
        }
    }
    private fun await(description: String, timeout: Long=10000, condition: () -> Boolean) {
        val until=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<until) {
            var ready=false; inst.runOnMainSync { ready=condition() }
            if(ready) return
            SystemClock.sleep(50)
        }
        error(description)
    }
    private fun secure(key: String) = Settings.Secure.getString(inst.targetContext.contentResolver,key)
    private fun putSecure(key: String,value: String?) {
        check(key in listOf("enabled_accessibility_services","accessibility_enabled"))
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        val pipes=automation.executeShellCommandRw("sh")
        val quote=value?.let { "'"+it.replace("'","'\"'\"'")+"'" }
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write(((if(value==null) "settings delete secure $key" else "settings put secure $key $quote")+"\nexit\n").toByteArray())
        }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        val until=SystemClock.uptimeMillis()+3000
        while(secure(key)!=value && SystemClock.uptimeMillis()<until) SystemClock.sleep(25)
        check(secure(key)==value)
    }
    private fun connect(previous: Map<String,String?>): Int {
        automation=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val flags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        var ready=false; inst.runOnMainSync { ready=MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected==true }
        if(!ready) {
            val own=ComponentName(inst.targetContext.packageName,MinisAccessibilityService::class.java.name)
            val others=previous.getValue("enabled_accessibility_services").orEmpty().split(':')
                .filter { ComponentName.unflattenFromString(it)!=own }.joinToString(":")
            putSecure("enabled_accessibility_services",others)
            await("Service did not disconnect") { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected!=true }
            // Framework disconnect is independently observed before adding the same service again.
            val deadline=SystemClock.uptimeMillis()+5000; var unbound=false
            while(!unbound && SystemClock.uptimeMillis()<deadline) {
                val state=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys accessibility")).use { it.readBytes().toString(Charsets.UTF_8) }
                val lines=state.lineSequence().filter { it.contains("Bound services:") || it.contains("Binding services:") }.toList()
                unbound=lines.isNotEmpty() && lines.none { it.contains("MinisAccessibilityService") }
                if(!unbound) SystemClock.sleep(50)
            }
            check(unbound)
            putSecure("enabled_accessibility_services",if(others.isEmpty()) own.flattenToString() else "$others:${own.flattenToString()}")
            putSecure("accessibility_enabled","1")
        }
        await("Actual service was not connected") { MinisAccessibilityService.getInstance()?.dotOverlay?.isConnected==true }
        return flags
    }
    private fun nativeText(node: AccessibilityNodeInfo,text: String): Boolean {
        if(node.isVisibleToUser && node.text?.toString()?.trim()==text) return true
        for(i in 0 until node.childCount) {
            val child=node.getChild(i) ?: continue
            try { if(nativeText(child,text)) return true } finally { child.recycle() }
        }
        return false
    }
    private fun host(service: MinisAccessibilityService,ownedFocus:Int?=null): Boolean {
        val scene=service.dotScreen.currentSceneKey() ?: return false
        val id=service.dotScreen.hostWindowId() ?: return false
        val windows=service.windows
        return try { scene.packageName==inst.context.packageName && scene.windowId==id &&
            windows.any { window ->
                val root=window.root
                try { window.id==id && window.type==AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive || ownedFocus?.let { owned -> windows.any { it.id==owned && it.isActive } }==true) &&
                    (window.isFocused || ownedFocus?.let { owned -> windows.any { it.id==owned && it.isFocused } }==true) &&
                    root?.packageName?.toString()==inst.context.packageName && nativeText(root,ForegroundApiHostFixture.MARKER) }
                finally { root?.recycle() }
            } && windows.none { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } finally { windows.forEach { it.recycle() } }
    }
    private fun overlayText(service: MinisAccessibilityService,text: String,view:View?=service.dotOverlay.panelView,freshCare:Boolean=false): Boolean {
        if(view?.isAttachedToWindow!=true) return false
        val actualNode=view.createAccessibilityNodeInfo()
        val ownedId=try { actualNode.windowId } finally { actualNode.recycle() }
        if(!host(service,ownedId)) return false
        val care=if(freshCare) field(service.dotInteraction,"careWindow") as? CareOverlayWindow ?: return false else null
        fun currentCare():Boolean {
            if(care==null || ownedId<0 || !view.isAttachedToWindow ||
                field(service.dotInteraction,"careWindow")!==care || field(care,"card")!==view) return false
            val node=view.createAccessibilityNodeInfo()
            return try { node.windowId==ownedId } finally { node.recycle() }
        }
        if(freshCare) {
            if(android.os.Build.VERSION.SDK_INT<34 || !currentCare()) return false
            val before=automation.windows
            val registered=try { before.any { window ->
                val root=window.root
                try { window.id==ownedId && window.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    root?.packageName?.toString()==app.packageName && service.dotOverlay.ownsWindow(window) }
                finally { root?.recycle() }
            } } finally { before.forEach { it.recycle() } }
            if(!registered || !currentCare() || !automation.clearCache()) return false
        }
        val windows=automation.windows
        return try { windows.filter { it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.any { window ->
            val root=window.root
            try { window.id==ownedId && root?.packageName?.toString()==app.packageName &&
                (!freshCare || (currentCare() && service.dotOverlay.ownsWindow(window) && host(service,ownedId))) &&
                nativeText(root,text) && (!freshCare || currentCare()) } finally { root?.recycle() }
        } } finally { windows.forEach { it.recycle() } }
    }
    private fun rect(view: View): RectPx {
        check(view.isAttachedToWindow && view.width>0 && view.height>0)
        val at=IntArray(2); view.getLocationOnScreen(at)
        return RectPx(at[0].toFloat(),at[1].toFloat(),(at[0]+view.width).toFloat(),(at[1]+view.height).toFloat())
    }
    private fun publicSnapshot(service: MinisAccessibilityService,snapshot: ScreenSnapshot): Pair<NodeDto,NodeDto> {
        var admittedHost=host(service)
        if(!admittedHost) {
            val care=field(service.dotInteraction,"careWindow") as? CareOverlayWindow
            val card=care?.let { field(it,"card") as? View }?.takeIf { it.isAttachedToWindow }
            val ownedId=card?.createAccessibilityNodeInfo()?.let { node -> try { node.windowId } finally { node.recycle() } }
            if(ownedId!=null && ownedId>=0 && care!=null && card!=null) {
                val windows=service.windows
                val owned=try { windows.any { window ->
                    if(window.id!=ownedId) false else {
                        val root=window.root
                        try { window.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                            root?.packageName?.toString()==app.packageName && service.dotOverlay.ownsWindow(window) }
                        finally { root?.recycle() }
                    }
                } } finally { windows.forEach { it.recycle() } }
                if(owned && card.isAttachedToWindow && field(care,"card")===card && field(service.dotInteraction,"careWindow")===care)
                    admittedHost=host(service,ownedId)
            }
        }
        check(admittedHost && snapshot.image==null && snapshot.scene==service.dotScreen.currentSceneKey() && snapshot.imeBounds==null)
        check(snapshot.scene.packageName==inst.context.packageName && snapshot.nodes.size in 3..16)
        val allowed=setOf(ForegroundApiHostFixture.ONE,ForegroundApiHostFixture.TWO,ForegroundApiHostFixture.MARKER)
        snapshot.nodes.forEach { node ->
            check(!node.password && (node.text.isNullOrEmpty() || node.text in allowed) &&
                (node.description.isNullOrEmpty() || node.description in allowed) &&
                (node.viewId==null || node.viewId=="android:id/content" || node.viewId.startsWith(inst.context.packageName+":id/")))
        }
        val one=snapshot.nodes.single { it.description==ForegroundApiHostFixture.ONE }
        val two=snapshot.nodes.single { it.description==ForegroundApiHostFixture.TWO }
        check(one.ref!=two.ref && one.editable && two.editable && one.enabled && two.enabled)
        check(listOf(one,two).all { node -> node.bounds.left>=snapshot.bounds.left && node.bounds.top>=snapshot.bounds.top &&
            node.bounds.right<=snapshot.bounds.right && node.bounds.bottom<=snapshot.bounds.bottom && node.bounds.width>0 && node.bounds.height>0 })
        return one to two
    }
    private fun startHost(service:MinisAccessibilityService) {
        val component=ComponentName(inst.context,ForegroundApiHostFixture::class.java)
        val info=app.packageManager.getActivityInfo(component,0)
        check(info.packageName==inst.context.packageName && info.name==ForegroundApiHostFixture::class.java.name && info.exported)
        inst.context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Public Java host must be active/focused with no keyboard") { host(service) }
    }
    private fun inspectBothPublicFields(service:MinisAccessibilityService): Pair<NodeDto,NodeDto> = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val result=service.dotScreen.captureNodes(1)
            val snapshot=(result as? CaptureOutcome.Success)?.snapshot ?: error("Public native nodes unavailable")
            try { publicSnapshot(service,snapshot).also { check(!it.first.focused && !it.second.focused) } }
            finally { snapshot.close() }
        }
    }
    private fun idle(service: MinisAccessibilityService) {
        check(service.dotOverlay.panelView==null && !service.dotInteraction.careSession.visible.value)
        check(service.dotInteraction.careSession.controller.state.value.state==CareState.Idle)
        check(field(service.dotInteraction,"careWindow")==null)
        check(!io.github.nanomuse.hub.Hub.connected.value && !io.github.nanomuse.hub.Hub.isConnected && io.github.nanomuse.hub.Hub.devices.value.isEmpty())
        check(app.providerRepository.configLoaded.value)
        val config=app.providerRepository.config.value
        check(config.instances.isEmpty() && config.modelEntries.isEmpty() && config.modelGroups.isEmpty() &&
            config.agentLoopModelEntryIds.isEmpty() && config.agentLoopGroupIds.isEmpty() &&
            config.defaultPrimaryGroupId==null && config.defaultSubGroupId==null && config.voiceInputGroupId==null &&
            config.voiceOutputGroupId==null && config.visionGroupId==null)
        check(!io.github.nanomuse.cloud.NanoMuseCloud.isSignedIn(app))
        check(field(ChatViewModelStore,"pendingTransfer")==null)
        check(field(io.github.nanomuse.dot.voice.DotVoiceRuntime,"entry")==null)
        check(field(com.openminis.app.logging.AppLogger,"enabled")==false && field(com.openminis.app.logging.AppLogger,"captureActive")==false)
        val owners=field(CareOperationGate,"owners")!!
        check(field(owners,"count")==0)
        // Inspect only busy booleans, never foreign messages, prefs, files or credentials.
        val stores=field(ChatViewModelStore,"stores") as Map<*,*>
        stores.values.forEach { store ->
            val map=field(store!!,"map") as Map<*,*>
            map.values.filterIsInstance<ChatViewModel>().forEach { vm ->
                check(!vm.isStreaming.value && !vm.isCompacting.value && vm.promptQueue.value.isEmpty())
            }
        }
    }
    private fun careContext(planner: CarePlanner,goal: String,snapshot: ScreenSnapshot,history: List<CareProposal>): String {
        fun invoke(name:String,value:Any): JsonObject = planner.javaClass.declaredMethods.single { it.name==name }
            .apply { isAccessible=true }.invoke(planner,value) as JsonObject
        val validator=CareProposalValidator()
        return buildJsonObject {
            put("goal",goal); put("snapshotId",snapshot.id); put("scene",invoke("sceneJson",snapshot.scene)); put("capturedAtMs",snapshot.capturedAtMs)
            put("nodes",JsonArray(snapshot.nodes.filter { validator.eligible(it,snapshot) }.map { invoke("nodeJson",it) }))
            put("verified",JsonArray(history.map { invoke("proposalJson",it) }))
        }.toString()
    }
    // Inspect only fixture-scoped counts, never arbitrary sync owner or mapping contents.
    // Holding the sync transaction keeps all three inventories coherent through chat deletion.
    private suspend fun <T> absentSync(id:String,messageIds:Set<String>,deleteOwnedChat:suspend () -> T):T {
        check(UUID.fromString(id).toString()==id && messageIds.size<=2)
        val database=io.github.nanomuse.sync.SyncDatabase.get(app)
        return database.withTransaction {
            val sql=database.openHelper.readableDatabase
            fun count(query:String,args:List<String>):Long = sql.query(androidx.sqlite.db.SimpleSQLiteQuery(query,args.toTypedArray())).use { cursor ->
                check(cursor.moveToFirst()); cursor.getLong(0).also { check(it>=0) }
            }
            val messageQuery="SELECT COUNT(*) FROM sync_messages WHERE sessionId = ?" +
                if(messageIds.isEmpty()) "" else " OR messageId IN (${messageIds.joinToString(",") { "?" }})"
            withAbsentForegroundSync(
                ownerCount={ count("SELECT COUNT(*) FROM session_owners WHERE sessionId = ?",listOf(id)) },
                conversationCount={ count("SELECT COUNT(*) FROM sync_conversations WHERE sessionId = ?",listOf(id)) },
                messageCount={ count(messageQuery,listOf(id)+messageIds.sorted()) },
            ) { deleteOwnedChat() }
        }
    }
    private fun ownedCleanup(id:String,title:String,context:ForegroundPublicContext,recorded:JSONArray) = runBlocking {
        val row=app.chatRepository.getSession(id) ?: return@runBlocking
        val messages=app.chatRepository.dao.loadMessages(id)
        check(row.id==id && row.title==title && row.modelId=="deepseek-flash" && row.modelBinding==null && row.memoryEnabled==0)
        val expected=(0 until recorded.length()).associate { index -> recorded.getJSONObject(index).let { it.getString("id") to (it.getString("role") to it.getString("parts_json")) } }
        check(messages.size==expected.size && messages.size<=2 && messages.all { message ->
            val parts=JSONArray(message.partsJson)
            expected[message.id]==(message.role to message.partsJson) && message.sessionId==id && parts.length()==1 && parts.getJSONObject(0).let { part ->
                part.keys().asSequence().toSet()==setOf("type","value") && part.getString("type")=="text" &&
                    ((message.role=="user" && part.getString("value")==REQUEST) ||
                        (message.role=="assistant" && part.getString("value").trim()=="READY"))
            }
        }) { "Unknown owned rows preserved" }
        absentSync(id,expected.keys) {
            app.chatRepository.dao.deleteMessages(id); app.chatRepository.dao.deleteSession(id)
        }
        check(!File(context.filesDir,"media").exists() && !java.nio.file.Files.isSymbolicLink(File(context.filesDir,"media").toPath()))
        removeOwnedSessionDirectories(context.filesDir,id)
        // Any ordinary app-owned session directory must be public and empty before removal.
        File(app.filesDir,"minis-sessions/$id").takeIf { it.exists() }?.let { directory ->
            check(!java.nio.file.Files.isSymbolicLink(directory.toPath()) && directory.listFiles().orEmpty().isEmpty()); check(directory.delete())
        }
        val badges=com.openminis.app.service.SessionBadgeStore.byId.value
        check(badges[id].orEmpty().all { it==com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED })
        com.openminis.app.service.SessionBadgeStore.remove(id,com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED)
        check(com.openminis.app.service.SessionBadgeStore.byId.value==badges-id)
        check(app.chatRepository.getSession(id)==null)
        writePublic(File(ownerDirectory,"foreground-journal.json"),journal(id,title,recorded,true))
    }
    /** Keyless characterization only; the existing foreground preflight receipt stays unchanged. */
    private fun keylessCareAdmissionDiagnostic(actual: MinisAccessibilityService) {
        val session=actual.dotInteraction.careSession; val controller=session.controller
        val originalActive=ChatViewModelStore.activeSessionId
        val originalSample=field(controller,"sample"); val originalPlan=field(controller,"plan")
        val conversation=session.conversation
        @Suppress("UNCHECKED_CAST") val bubbles=field(conversation,"mutable") as MutableStateFlow<List<CareBubble>>
        val originalSequence=field(conversation,"userSequence") as Long
        val owners=field(CareOperationGate,"owners")!!
        var samples=0; var plans=0; var ownedWindow:CareOverlayWindow?=null; var installed=false; var publicHost:Int?=null
        var focusProbe=false
        val focusInstruction="Focus FOREGROUND_FOCUS_ONE."
        val jobs=mutableListOf<Job>()
        var originalOwners=0; var originalAutomatic=false
        val observed=JSONObject().put("probe","initial").put("sampleOutcome","NotSampled").put("sampleCode","none")
            .put("snapshotMatchesCurrent",false).put("publicCheckPassed",false)
        val codes=setOf("host_unavailable","nodes_unavailable","scene_changed","locked","accessibility_unavailable",
            "timeout","locked_or_disconnected","permission_denied","care_timeout","care_unavailable",
            "keyless_public_host_changed","keyless_public_check_failed","keyless_plan_reached")
        fun emit(phase:String) {
            val state=controller.state.value; val windows=actual.windows
            val card=(field(actual.dotInteraction,"careWindow") as? CareOverlayWindow)?.let { field(it,"card") as? View }
            val careId=card?.takeIf { it.isAttachedToWindow }?.createAccessibilityNodeInfo()?.let { node ->
                try { node.windowId } finally { node.recycle() }
            }
            val hostId=actual.dotScreen.hostWindowId()
            try {
                val hostWindow=windows.firstOrNull { it.id==hostId }
                val careWindow=windows.firstOrNull { it.id==careId }
                val code=state.errorCode?.let { if(it in codes) it else "other" } ?: "none"
                val entry=JSONObject(observed.toString()).put("phase",phase).put("state",state.state.name).put("code",code)
                    .put("samples",samples).put("plans",plans).put("planReached",plans>0)
                    .put("operationOwners",field(owners,"count") as Int).put("automatic",field(controller,"automatic") as Boolean)
                    .put("hostActive",hostWindow?.isActive==true).put("hostFocused",hostWindow?.isFocused==true)
                    .put("careAttached",card?.isAttachedToWindow==true).put("careActive",careWindow?.isActive==true)
                    .put("careFocused",careWindow?.isFocused==true).put("careOwned",careWindow?.let(actual.dotOverlay::ownsWindow)==true)
                    .put("strictHost",host(actual)).put("hostWithOwnCareFocus",careId?.let { host(actual,it) }==true)
                android.util.Log.i("ForegroundCareKeyless",entry.toString()) // Fixed whitelist only; no node text/exception/payload.
            } finally { windows.forEach { it.recycle() } }
        }
        inst.runOnMainSync {
            check(controller.state.value==CareUiState() && !session.visible.value && !session.editingGoal.value)
            check(field(actual.dotInteraction,"careWindow")==null && field(controller,"work")==null && bubbles.value.isEmpty())
            val adapter=actual.dotInteraction.javaClass.getDeclaredMethod("getAdapter").apply { isAccessible=true }.invoke(actual.dotInteraction) as DotChatAdapter
            check(adapter.binding.value==null && adapter.session==null)
            originalOwners=field(owners,"count") as Int; originalAutomatic=field(controller,"automatic") as Boolean
            publicHost=actual.dotScreen.hostWindowId(); emit("before")
        }
        try {
            val sample:suspend(Long)->CaptureOutcome = { generation ->
                samples++
                val current=actual.dotScreen.currentSceneKey()
                val result=if(current==null || current.packageName!=inst.context.packageName || current.windowId!=publicHost)
                    CaptureOutcome.Unavailable("keyless_public_host_changed") else actual.dotScreen.captureNodes(generation)
                when(result) {
                    is CaptureOutcome.Success -> {
                        observed.put("sampleOutcome","Success").put("sampleCode","none")
                            .put("snapshotMatchesCurrent",result.snapshot.scene==actual.dotScreen.currentSceneKey())
                            .put("nodeCount",result.snapshot.nodes.size).put("imageAbsent",result.snapshot.image==null)
                    }
                    is CaptureOutcome.Unavailable -> observed.put("sampleOutcome","Unavailable")
                        .put("sampleCode",if(result.code in codes) result.code else "other")
                }
                emit("sample_return")
                result // Ownership goes to the unchanged actual controller, which closes it.
            }
            val plan:suspend(String,ScreenSnapshot,List<CareProposal>)->CarePlanResult = { goal,snapshot,history ->
                plans++
                check(goal==GOAL && history.isEmpty())
                // Diagnostic booleans only: the admission below still uses the unchanged strict helper.
                observed.put("sceneMatchesCurrent",snapshot.scene==actual.dotScreen.currentSceneKey())
                    .put("imeAbsent",snapshot.imeBounds==null).put("imageAbsent",snapshot.image==null)
                    .put("publicShapePassed",runCatching {
                        check(snapshot.scene.packageName==inst.context.packageName && snapshot.nodes.size in 3..16)
                        val allowed=setOf(ForegroundApiHostFixture.ONE,ForegroundApiHostFixture.TWO,ForegroundApiHostFixture.MARKER)
                        snapshot.nodes.forEach { node ->
                            check(!node.password && (node.text.isNullOrEmpty() || node.text in allowed) &&
                                (node.description.isNullOrEmpty() || node.description in allowed) &&
                                (node.viewId==null || node.viewId=="android:id/content" || node.viewId.startsWith(inst.context.packageName+":id/")))
                        }
                        val one=snapshot.nodes.single { it.description==ForegroundApiHostFixture.ONE }
                        val two=snapshot.nodes.single { it.description==ForegroundApiHostFixture.TWO }
                        check(one.ref!=two.ref && one.editable && two.editable && one.enabled && two.enabled)
                        check(listOf(one,two).all { node -> node.bounds.left>=snapshot.bounds.left && node.bounds.top>=snapshot.bounds.top &&
                            node.bounds.right<=snapshot.bounds.right && node.bounds.bottom<=snapshot.bounds.bottom && node.bounds.width>0 && node.bounds.height>0 })
                    }.isSuccess)
                emit("plan_enter")
                val accepted=runCatching { publicSnapshot(actual,snapshot) }.isSuccess
                observed.put("publicCheckPassed",accepted)
                emit("strict_public_check")
                if(focusProbe) {
                    val target=publicSnapshot(actual,snapshot).first
                    check(!target.focused && controller.verifiedSteps.value.isEmpty())
                    val proposal=CareProposal(snapshot.id,snapshot.scene,target.ref,CareAction.Focus,focusInstruction,null,
                        CareExpectation.Focused(target.ref),1f)
                    check(CareProposalValidator().validate(proposal,snapshot))
                    CarePlanResult.Step(proposal)
                } else CarePlanResult.Unavailable(if(accepted) "keyless_plan_reached" else "keyless_public_check_failed")
            }
            inst.runOnMainSync {
                installed=true; set(controller,"sample",sample); set(controller,"plan",plan)
                actual.dotInteraction.handle(DotIntent.Triple)
                ownedWindow=field(actual.dotInteraction,"careWindow") as? CareOverlayWindow
                session.submitGoal(GOAL)
            }
            await("Keyless Care diagnostic did not settle",10000) {
                controller.state.value.state in setOf(CareState.Recovering,CareState.Paused)
            }
            await("Actual Care card window was not observable",10000) {
                val card=ownedWindow?.let { field(it,"card") as? View }
                if(card?.isAttachedToWindow!=true) false else {
                    val node=card.createAccessibilityNodeInfo(); val id=try { node.windowId } finally { node.recycle() }
                    val windows=actual.windows
                    try { windows.any { it.id==id && actual.dotOverlay.ownsWindow(it) } }
                    finally { windows.forEach { it.recycle() } }
                }
            }
            inst.runOnMainSync { emit("settled_attached") }
            fun ownedCardFocused():Boolean {
                check(field(actual.dotInteraction,"careWindow")===ownedWindow)
                val card=ownedWindow?.let { field(it,"card") as? View }
                return if(card?.isAttachedToWindow!=true) false else {
                    val node=card.createAccessibilityNodeInfo(); val id=try { node.windowId } finally { node.recycle() }
                    val windows=actual.windows
                    try { windows.any { it.id==id && it.isActive && it.isFocused && actual.dotOverlay.ownsWindow(it) } }
                    finally { windows.forEach { it.recycle() } }
                }
            }
            await("Owned Care card did not become active and focused",10000) { ownedCardFocused() }
            var firstSamples=0; var firstPlans=0
            inst.runOnMainSync {
                check(ownedCardFocused()) // Confirm admission again on the same Main turn as reobserve.
                check(field(owners,"count")==0 && field(controller,"automatic")==false && field(controller,"active")==true)
                check(controller.state.value.state==CareState.Recovering)
                (field(controller,"work") as? Job)?.let { jobs.add(it) } // reobserve invalidates this handle; retain it for cleanup join.
                firstSamples=samples; firstPlans=plans
                observed.put("probe","stable_care_focus")
                emit("before_reobserve")
                session.reobserve()
            }
            await("Stable Care focus reobserve did not reach sample and plan",10000) {
                samples>firstSamples && plans>firstPlans && controller.state.value.state in setOf(CareState.Recovering,CareState.Paused)
            }
            inst.runOnMainSync { emit("settled_reobserve") }
            inst.runOnMainSync {
                check(ownedCardFocused() && field(owners,"count")==0 && field(controller,"automatic")==false)
                check(field(controller,"active")==true && controller.state.value.state==CareState.Recovering)
                (field(controller,"work") as? Job)?.let { jobs.add(it) }
                focusProbe=true; observed.put("probe","valid_focus_ui")
                session.reobserve()
            }
            await("Local valid Focus did not reach actual AwaitingUser",10000) { controller.state.value.state==CareState.AwaitingUser }
            fun focusConditions(freshCare:Boolean=false):JSONObject {
                val state=controller.state.value; val target=state.target; val proposal=state.proposal
                val window=ownedWindow; val exactWindow=field(actual.dotInteraction,"careWindow")===window && window!=null
                val card=window?.let { field(it,"card") as? View }
                val measured=card?.takeIf { it.isAttachedToWindow && it.width>0 && it.height>0 }?.let(::rect)
                val bubble=window?.let { field(it.focus,"instruction") as? RectPx }
                val descriptionMatch=target?.description==ForegroundApiHostFixture.ONE
                val refMatch=target!=null && proposal!=null && proposal.targetRef==target.ref
                val instructionMatch=proposal?.instruction==focusInstruction
                val publicTarget=descriptionMatch && refMatch && instructionMatch && target!=null &&
                    proposal?.expectation==CareExpectation.Focused(target.ref)
                val awaiting=state.state==CareState.AwaitingUser
                val boundsMatch=window!=null && target!=null && window.focus.bounds==target.bounds
                val keyMatch=window!=null && field(window.focus,"identity")==session.instructionKey()
                val focusAttached=window?.focus?.view?.isAttachedToWindow==true
                val bubblePositive=bubble!=null && bubble.width>0 && bubble.height>0
                val contained=bubble!=null && measured!=null && bubble.left>=measured.left && bubble.top>=measured.top &&
                    bubble.right<=measured.right && bubble.bottom<=measured.bottom
                val above=measured!=null && target!=null && measured.bottom<=target.bounds.top
                val goalText=card?.isAttachedToWindow==true && overlayText(actual,GOAL,card,freshCare)
                val instructionText=card?.isAttachedToWindow==true && overlayText(actual,focusInstruction,card,freshCare)
                val guide=awaiting && target?.description==ForegroundApiHostFixture.ONE && boundsMatch && keyMatch && focusAttached &&
                    bubblePositive && contained && above && goalText && instructionText
                val result=JSONObject().put("phase","valid_focus_ui").put("state",state.state.name)
                    .put("code",state.errorCode?.let { if(it in codes) it else "other" } ?: "none")
                    .put("samples",samples).put("plans",plans).put("verifiedSteps",controller.verifiedSteps.value.size)
                    .put("exactWindow",exactWindow).put("publicTargetAndRef",publicTarget).put("awaitingUser",awaiting)
                    .put("targetDescriptionMatch",descriptionMatch).put("targetRefMatch",refMatch).put("instructionMatch",instructionMatch)
                    .put("cardAttached",card?.isAttachedToWindow==true).put("cardMeasured",measured!=null)
                    .put("focusBoundsMatch",boundsMatch).put("instructionKeyMatch",keyMatch).put("focusAttached",focusAttached)
                    .put("bubblePresent",bubble!=null).put("bubblePositive",bubblePositive).put("bubbleContained",contained)
                    .put("panelAboveTarget",above).put("goalAxText",goalText).put("instructionAxText",instructionText)
                    .put("ownCardActiveFocused",exactWindow && ownedCardFocused()).put("strictHost",host(actual)).put("guidePassed",guide)
                fun geometry(name:String,bounds:RectPx?) {
                    if(bounds!=null && listOf(bounds.left,bounds.top,bounds.right,bounds.bottom).all { it.isFinite() })
                        result.put(name,JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
                }
                if(publicTarget) { geometry("targetBounds",target?.bounds); geometry("cardBounds",measured); geometry("bubbleBounds",bubble) }
                return result
            }
            var refreshNode:AccessibilityNodeInfo?=null; var refreshCardId:Int?=null; var refreshTargetRef:String?=null
            var beforeVisible=false
            fun publicAxEvidence(captureInstruction:Boolean=false):JSONObject {
                val state=controller.state.value; val target=checkNotNull(state.target); val proposal=checkNotNull(state.proposal)
                check(state.state==CareState.AwaitingUser && target.description==ForegroundApiHostFixture.ONE &&
                    proposal.targetRef==target.ref && proposal.action==CareAction.Focus && proposal.instruction==focusInstruction &&
                    proposal.expectation==CareExpectation.Focused(target.ref))
                val window=checkNotNull(ownedWindow); check(field(actual.dotInteraction,"careWindow")===window)
                val card=field(window,"card") as View; check(card.isAttachedToWindow)
                val native=card.createAccessibilityNodeInfo()
                val cardId=try { native.windowId } finally { native.recycle() }; check(cardId>=0 && host(actual,cardId))
                if(captureInstruction) { refreshCardId=cardId; refreshTargetRef=target.ref }
                val bubble=field(window.focus,"instruction") as? RectPx
                val names=listOf("exactTextAny","exactTextVisible","exactDescriptionAny","exactDescriptionVisible",
                    "normalizedTextAny","normalizedTextVisible","containsTextAny","containsTextVisible")
                val expected=listOf(GOAL,focusInstruction); val counts=Array(2) { IntArray(names.size) }
                val bounds=JSONArray(); var boundsOmitted=0; var visited=0; var nullChildren=0; var truncated=0
                val spaces=Regex("\\s+")
                fun visit(node:AccessibilityNodeInfo,depth:Int) {
                    visited++
                    val text=node.text?.toString()?.trim(); val description=node.contentDescription?.toString()?.trim()
                    val visible=node.isVisibleToUser
                    expected.forEachIndexed { index,value ->
                        val matches=listOf(text==value,description==value,text?.replace(spaces," ")==value,text?.contains(value)==true)
                        matches.forEachIndexed { match,yes -> if(yes) { counts[index][match*2]++; if(visible) counts[index][match*2+1]++ } }
                    }
                    if(text==focusInstruction || description==focusInstruction) {
                        if(captureInstruction && text==focusInstruction && node.windowId==cardId && refreshNode==null) {
                            refreshNode=AccessibilityNodeInfo.obtain(node); beforeVisible=visible
                        }
                        if(bounds.length()<8) {
                            val r=android.graphics.Rect(); node.getBoundsInScreen(r)
                            val usable=r.width()>0 && r.height()>0
                            bounds.put(JSONObject().put("visible",visible).put("cardWindowIdMatch",node.windowId==cardId)
                                .put("positiveBounds",usable).put("bubbleOverlap",usable && bubble!=null &&
                                    r.left<bubble.right && r.right>bubble.left && r.top<bubble.bottom && r.bottom>bubble.top)
                                .put("bounds",JSONArray(listOf(r.left,r.top,r.right,r.bottom))))
                        } else boundsOmitted++
                    }
                    for(index in 0 until node.childCount) {
                        if(visited+nullChildren>=256 || depth>=32) { truncated+=node.childCount-index; break }
                        val child=node.getChild(index)
                        if(child==null) nullChildren++ else try { visit(child,depth+1) } finally { child.recycle() }
                    }
                }
                val windows=automation.windows
                try {
                    val overlay=checkNotNull(windows.singleOrNull { it.id==cardId && it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY })
                    check(actual.dotOverlay.ownsWindow(overlay))
                    val root=checkNotNull(overlay.root)
                    try { check(root.packageName?.toString()==app.packageName); visit(root,1) }
                    finally { root.recycle() }
                } finally { windows.forEach { it.recycle() } }
                if(captureInstruction) check(nullChildren==0 && truncated==0 && counts[1][0]==1 && refreshNode!=null)
                val result=JSONObject().put("exactOwnedRoot",true).put("visited",visited).put("nullChildren",nullChildren)
                    .put("truncatedBranches",truncated).put("instructionMatches",bounds).put("boundsOmitted",boundsOmitted)
                listOf("goal","instruction").forEachIndexed { index,label ->
                    val values=JSONObject(); names.forEachIndexed { metric,name -> values.put(name,counts[index][metric]) }
                    result.put(label,values)
                }
                return result
            }
            val until=SystemClock.uptimeMillis()+10000
            var focusResult=JSONObject()
            inst.runOnMainSync {
                android.util.Log.i("ForegroundCareFocusKeyless",focusConditions().put("phase","valid_focus_ui_enter")
                    .put("publicAxEvidence",publicAxEvidence()).toString())
            }
            do {
                inst.runOnMainSync { focusResult=focusConditions() }
                SystemClock.sleep(50)
            } while(SystemClock.uptimeMillis()<until)
            inst.runOnMainSync {
                check(controller.verifiedSteps.value.isEmpty())
                android.util.Log.i("ForegroundCareFocusKeyless",focusResult.put("publicAxEvidence",publicAxEvidence()).toString())
            }
            try {
                inst.runOnMainSync { publicAxEvidence(captureInstruction=true) }
                check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
                val node=checkNotNull(refreshNode)
                val success=node.refresh() // One read-only, cache-bypassing query for this exact public node.
                inst.runOnMainSync {
                    val state=controller.state.value; val target=checkNotNull(state.target); val proposal=checkNotNull(state.proposal)
                    check(field(actual.dotInteraction,"careWindow")===ownedWindow && state.state==CareState.AwaitingUser &&
                        target.description==ForegroundApiHostFixture.ONE && target.ref==refreshTargetRef && proposal.targetRef==target.ref &&
                        proposal.action==CareAction.Focus && proposal.instruction==focusInstruction &&
                        proposal.expectation==CareExpectation.Focused(target.ref))
                    val card=field(checkNotNull(ownedWindow),"card") as View; check(card.isAttachedToWindow)
                    val current=card.createAccessibilityNodeInfo()
                    try { check(current.windowId==refreshCardId && host(actual,current.windowId)) } finally { current.recycle() }
                    val bounds=android.graphics.Rect(); node.getBoundsInScreen(bounds)
                    android.util.Log.i("ForegroundCareRefreshKeyless",JSONObject().put("phase","settled_public_refresh")
                        .put("refreshAttempted",true).put("beforeVisible",beforeVisible).put("refreshSuccess",success)
                        .put("afterVisible",node.isVisibleToUser).put("afterExactText",node.text?.toString()?.trim()==focusInstruction)
                        .put("afterCardWindowIdMatch",node.windowId==refreshCardId).put("afterPositiveBounds",bounds.width()>0 && bounds.height()>0)
                        .put("afterRefreshGuide",focusConditions()).toString())
                }
            } finally { refreshNode?.recycle(); refreshNode=null }
            inst.runOnMainSync {
                val pre=focusConditions(); val fresh=focusConditions(freshCare=true)
                check(controller.verifiedSteps.value.isEmpty())
                fun flags(value:JSONObject)=JSONObject().apply {
                    listOf("exactWindow","publicTargetAndRef","awaitingUser","targetDescriptionMatch","targetRefMatch",
                        "instructionMatch","cardAttached","cardMeasured","focusBoundsMatch","instructionKeyMatch","focusAttached",
                        "bubblePresent","bubblePositive","bubbleContained","panelAboveTarget","goalAxText","instructionAxText",
                        "ownCardActiveFocused","strictHost","guidePassed").forEach { put(it,value.getBoolean(it)) }
                }
                android.util.Log.i("ForegroundCareCacheKeyless",JSONObject().put("phase","settled_cache_fresh")
                    .put("pre",flags(pre)).put("fresh",flags(fresh)).toString())
                check(fresh.getBoolean("guidePassed")) { "Cache-fresh public Focus guide did not pass" }
            }
        } finally {
            if(installed) {
                var failures=0
                fun restore(action:()->Unit) { try { action() } catch(_:Throwable) { failures++ } }
                restore { inst.runOnMainSync { emit("before_cleanup") } }
                restore { inst.runOnMainSync {
                    (field(controller,"work") as? Job)?.let { jobs.add(it); it.cancel() }
                } }
                restore { inst.runOnMainSync {
                    ownedWindow?.let { window ->
                        (field(window,"job") as? Job)?.let { jobs.add(it); it.cancel() }
                    }
                } }
                restore { inst.runOnMainSync { controller.cancel() } } // Invalidate even if disconnect's focus/speech callbacks throw.
                restore { inst.runOnMainSync { session.disconnect() } }
                restore { inst.runOnMainSync {
                    check(field(actual.dotInteraction,"careWindow")===ownedWindow) // Never close another owner's window.
                    ownedWindow?.let { window ->
                        window.close()
                        set(actual.dotInteraction,"careWindow",null)
                    }
                } }
                restore { runBlocking { withTimeout(5000) { jobs.distinct().forEach { it.join() } } } }
                restore { inst.runOnMainSync {
                    check(field(controller,"active")==false && controller.state.value==CareUiState() &&
                        field(controller,"work")==null && jobs.all { it.isCompleted }) { "Keyless Care callbacks remain isolated" }
                    set(controller,"sample",originalSample); set(controller,"plan",originalPlan)
                } }
                restore { inst.runOnMainSync {
                    bubbles.value=emptyList()
                    conversation.javaClass.getDeclaredField("userSequence").apply { isAccessible=true }.setLong(conversation,originalSequence)
                    check(controller.state.value==CareUiState() && !session.visible.value && !session.editingGoal.value && controller.verifiedSteps.value.isEmpty())
                    check(ChatViewModelStore.activeSessionId==originalActive)
                    check(field(owners,"count")==originalOwners && field(controller,"automatic")==originalAutomatic)
                    emit("restored")
                } }
                restore { await("Public host focus did not return after diagnostic") { host(actual) } }
                restore { inspectBothPublicFields(actual) } // Same strict public whitelist; both fields remain unfocused.
                restore { android.util.Log.i("ForegroundCareKeyless",JSONObject().put("phase","cleanup_finished").put("cleanupFailures",failures).toString()) }
                check(failures==0) { "Keyless Care cleanup incomplete" }
            }
        }
    }

    @Test fun canonicalChatThenObservableCareNext() {
        val args=InstrumentationRegistry.getArguments()
        val suppliedOwner=args.getString("foregroundOwner")
        assumeTrue("Fresh reviewed owner input required",suppliedOwner!=null)
        owner=requireNotNull(suppliedOwner); check(UUID.fromString(owner).toString()==owner)
        source=requireNotNull(args.getString("foregroundSource")); check(source.matches(Regex("[0-9a-f]{40}")))
        val ownerRoot=File(app.cacheDir,"foreground-api-proof-$owner")
        check(ownerRoot.canonicalFile.parentFile==app.cacheDir.canonicalFile && ownerRoot.isDirectory && !java.nio.file.Files.isSymbolicLink(ownerRoot.toPath()))
        ownerDirectory=ownerRoot
        if(args.getString("foregroundPreflightOnly")=="true") {
            val previous=listOf("enabled_accessibility_services","accessibility_enabled").associateWith(::secure)
            var flags:Int?=null
            try {
                flags=connect(previous); val actual=requireNotNull(MinisAccessibilityService.getInstance())
                await("Loaded empty configuration required") { app.providerRepository.configLoaded.value }
                inst.runOnMainSync { idle(actual) }
                startHost(actual); val fields=inspectBothPublicFields(actual)
                keylessCareAdmissionDiagnostic(actual)
                stage("host_preflight_passed",Bundle().apply {
                    putBoolean("actualHostActiveFocused",true); putBoolean("bothPublicFieldsEditable",true); putBoolean("keyboardHidden",true)
                    putFloat("oneLeft",fields.first.bounds.left); putFloat("oneTop",fields.first.bounds.top); putFloat("oneRight",fields.first.bounds.right); putFloat("oneBottom",fields.first.bounds.bottom)
                    putFloat("twoLeft",fields.second.bounds.left); putFloat("twoTop",fields.second.bounds.top); putFloat("twoRight",fields.second.bounds.right); putFloat("twoBottom",fields.second.bounds.bottom)
                    putInt("exchanges",0)
                })
                awaitStreamRelease(sequence,"release-preflight",30000)
            } finally {
                if(::automation.isInitialized) {
                    flags?.let { saved -> automation.serviceInfo=automation.serviceInfo.apply { this.flags=saved } }
                    previous.forEach { (key,value) -> putSecure(key,value) }
                }
            }
            return
        }
        if(args.getString("foregroundCleanupOnly")=="true") {
            val file=File(ownerRoot,"foreground-journal.json"); val metadata=Os.lstat(file.path)
            check(OsConstants.S_ISREG(metadata.st_mode) && metadata.st_mode and 4095==384 && metadata.st_uid==Process.myUid() && metadata.st_nlink==1L && metadata.st_size in 1..32768)
            val record=JSONObject(file.readText())
            record.exact("schema","owner","source","session_id","title","model","memory_enabled","cleanup_state","allowed_user","allowed_assistant","owned_rows")
            check(record.getInt("schema")==1 && record.getString("owner")==owner && record.getString("source")==source &&
                record.getString("model")=="deepseek-flash" && record.getInt("memory_enabled")==0 &&
                record.getString("allowed_user")==REQUEST && record.getString("allowed_assistant")=="READY")
            val cleanupId=record.getString("session_id")
            check(cleanupId==UUID.nameUUIDFromBytes("foreground-session:$owner".toByteArray()).toString() && record.getString("title")=="FOREGROUND_API_PUBLIC_$owner")
            check(record.getString("cleanup_state") in setOf("pending","complete"))
            if(record.getString("cleanup_state")=="pending") ownedCleanup(cleanupId,record.getString("title"),ForegroundPublicContext(app,ownerRoot),record.getJSONArray("owned_rows"))
            removeOwnedPublicDirectories(ownerRoot)
            stage("case_finally",Bundle().apply { putBoolean("assertionsPassed",false); putBoolean("ownedCleanupPassed",true); putInt("cleanupFailures",0); putInt("exchanges",0); putBoolean("requiresRootStoppedBaselineEquality",true) })
            return
        }
        val handoff=File(ownerRoot,PrivateProofInput.INPUT_NAME)
        assumeTrue("No credential handoff; no native/API work",handoff.exists())
        val admissionFile=File(ownerRoot,"foreground-admission.json")
        val stat=Os.lstat(admissionFile.path)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_mode and 4095==384 && stat.st_uid==Process.myUid() && stat.st_nlink==1L && stat.st_size in 1..4096)
        val bytes=admissionFile.readBytes()
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest==args.getString("foregroundAdmissionSha256"))
        val admission=JSONObject(bytes.toString(Charsets.UTF_8))
        val gate=ForegroundProofContract(admission)
        check(gate.owner==owner && admission.getString("source")==source)
        val previous=listOf("enabled_accessibility_services","accessibility_enabled").associateWith(::secure)
        val originalActive=ChatViewModelStore.activeSessionId
        var flags:Int?=null; var service:MinisAccessibilityService?=null; var vm:ChatViewModel?=null
        var context:ForegroundPublicContext?=null; var id:String?=null; var title:String?=null
        var originalSample:Any?=null; var originalPlan:Any?=null; var originalEnabled:Boolean?=null
        var chatBound=false; var careBound=false
        val transports=mutableListOf<ForegroundApiTransport>()
        var passed=false
        try {
            flags=connect(previous); service=requireNotNull(MinisAccessibilityService.getInstance())
            val actual=service
            await("Idle loaded public admission was unavailable") { app.providerRepository.configLoaded.value }
            inst.runOnMainSync { idle(actual); originalEnabled=field(actual.dotOverlay,"enabled") as Boolean }
            startHost(actual); inspectBothPublicFields(actual)
            context=ForegroundPublicContext(app,ownerRoot)
            val public=context
            id=UUID.nameUUIDFromBytes("foreground-session:$owner".toByteArray()).toString(); val ownId=id
            title="FOREGROUND_API_PUBLIC_$owner"; val ownTitle=title
            check(io.github.nanomuse.chat.SessionAddenda.forPrompt(ownId)==null &&
                io.github.nanomuse.goals.GoalFlow.systemAddendum(public,ownId)==null && io.github.nanomuse.feed.FeedFlow.systemAddendum(public,ownId)==null)
            writePublic(File(ownerRoot,"foreground-journal.json"),journal(ownId,ownTitle))
            runBlocking { check(app.chatRepository.getSession(ownId)==null); app.chatRepository.dao.insertSession(ChatSessionEntity(
                id=ownId,title=ownTitle,modelId="deepseek-flash",createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis(),memoryEnabled=0)) }
            lateinit var adapter:DotChatAdapter
            inst.runOnMainSync {
                vm=ViewModelProvider(ChatViewModelStore.ownerFor(ownId),ChatViewModel.factory(ownId,app.chatRepository,app.providerRepository,public,null,null,null))[ChatViewModel::class.java]
                adapter=actual.dotInteraction.javaClass.getDeclaredMethod("getAdapter").apply { isAccessible=true }.invoke(actual.dotInteraction) as DotChatAdapter
                check(adapter.binding.value==null && adapter.session==null)
            }
            val canonical=requireNotNull(vm)
            await("Canonical fixture load did not finish") { (field(canonical,"sessionLoaded") as MutableStateFlow<*>).value==true }
            check(canonical.messages.value.isEmpty() && canonical.attachments.value.isEmpty() && canonical.promptQueue.value.isEmpty() && !canonical.memoryEnabled.value)
            // All admitted prompt inputs come from this isolated public context and static tools.
            val tools=AgentTools.makeAgentTools(supportsImageInput=false,visionGroupConfigured=false,memoryEnabled=false)
            lateinit var expectedSystem:String
            inst.runOnMainSync {
                expectedSystem=canonical.javaClass.getDeclaredMethod("buildSystemPrompt").apply { isAccessible=true }.invoke(canonical) as String
            }
            check(public.applicationContext===public && public.filesDir.canonicalPath.startsWith(ownerRoot.canonicalPath+File.separator))
            // The direct public DAO insertion bypasses normal owner claim, but startup
            // reconciliation is not assumed absent: fail before key consumption if it ran.
            runBlocking { absentSync(ownId,emptySet()) { Unit } }
            val key=checkNotNull(PrivateProofInput.consume(object:ContextWrapper(app) { override fun getCacheDir()=ownerRoot }))
            val chat=ForegroundApiTransport(ProofSlot.Chat,gate,key,expectedSystem,REQUEST,tools,checkpoint={ partial ->
                await("Real streaming delta was not visible in production panel") { canonical.isStreaming.value && overlayText(actual,partial) }
                val panel=requireNotNull(actual.dotOverlay.panelView)
                var panelWindowId=-1
                inst.runOnMainSync { val node=panel.createAccessibilityNodeInfo(); try { panelWindowId=node.windowId } finally { node.recycle() } }
                check(panelWindowId>=0)
                stage("chat_stream_visible",Bundle().apply { putBoolean("canonicalIdentity",true); putBoolean("actualStreaming",true); putString("publicText",partial); putInt("panelWindowId",panelWindowId) })
                recordRows(ownId,ownTitle)
                awaitStreamRelease(sequence)
            }); transports+=chat
            inst.runOnMainSync {
                set(canonical,"currentProvider",chat.provider); set(canonical,"currentModel",chat.provider.model)
                @Suppress("UNCHECKED_CAST") val modelName=field(canonical,"_modelName") as MutableStateFlow<String>; modelName.value="deepseek-flash"
                chatBound=true; val binding=adapter.bind(ownId); check(adapter.businessViewModel===canonical)
                check(ViewModelProvider(ChatViewModelStore.ownerFor(ownId),ChatViewModel.factory(ownId,app.chatRepository,app.providerRepository,app,null,null,null))[ChatViewModel::class.java]===canonical)
                actual.dotOverlay.setEnabled(true); actual.dotOverlay.showPanel(adapter) { adapter.binding.value?.let(adapter::cancel) }
                adapter.send(binding,REQUEST)
            }
            await("Canonical real stream did not complete",90000) { !canonical.isStreaming.value && chat.sawFinish && overlayText(actual,"READY") }
            check(chat.exchanges==1 && chat.httpStatus==200 && chat.sawDone && chat.sawUsage && chat.responseClosed && canonical.error.value==null)
            recordRows(ownId,ownTitle)
            gate.complete(ProofSlot.Chat); stage("chat_complete")
            val controller=actual.dotInteraction.careSession.controller
            var initialSnapshot:ScreenSnapshot?=null; var initialProposal:CareProposal?=null
            var careCalls=0
            originalSample=field(controller,"sample"); originalPlan=field(controller,"plan")
            val sample:suspend(Long)->CaptureOutcome = { generation -> actual.dotScreen.captureNodes(generation) }
            val plan:suspend(String,ScreenSnapshot,List<CareProposal>)->CarePlanResult = plan@{ goal,snapshot,history ->
                if(careCalls>=2) return@plan CarePlanResult.Unavailable("foreground_exchange_limit")
                check(goal==GOAL)
                val targets=publicSnapshot(actual,snapshot)
                val slot=if(careCalls==0) ProofSlot.CareInitial else ProofSlot.CareNext
                if(slot==ProofSlot.CareInitial) {
                    check(history.isEmpty() && controller.verifiedSteps.value.isEmpty() && !targets.first.focused && !targets.second.focused)
                    initialSnapshot=snapshot
                } else {
                    val first=requireNotNull(initialSnapshot); val proposal=requireNotNull(initialProposal)
                    val verified=controller.verifiedSteps.value.single()
                    check(history==listOf(proposal) && verified.proposal==proposal && verified.afterSnapshotId==snapshot.id && verified.afterScene==snapshot.scene &&
                        CareVerifier(first).evaluate(proposal,snapshot)==VerificationResult.Confirmed)
                    gate.authorizeNext(FocusLineage(first.id,snapshot.id,first.scene.version,snapshot.scene.version,CareVerifier.sameHost(first.scene,snapshot.scene),
                        proposal.targetRef,verified.proposal.targetRef,first.nodes.single { it.description==ForegroundApiHostFixture.ONE }.focused,
                        targets.first.focused,targets.second.focused,verified.origin.name,controller.verifiedSteps.value.size))
                }
                careCalls++
                // Existing private serializer is used only after the actual nodes/history whitelist.
                val shape=CarePlanner(chat.provider,"Public fields unavailable")
                val system=CarePlanner::class.java.getDeclaredField("SYSTEM_PROMPT").apply { isAccessible=true }.get(null) as String
                val request=careContext(shape,goal,snapshot,history)
                val transport=ForegroundApiTransport(slot,gate,key,system,request); transports+=transport
                val result=CarePlanner(transport.provider,"Public fields unavailable").next(goal,snapshot,history)
                val proposal=(result as? CarePlanResult.Step)?.proposal ?: error("Actual model did not propose a valid Focus step")
                val intended=if(slot==ProofSlot.CareInitial) targets.first else targets.second
                check(proposal.action==CareAction.Focus && proposal.targetRef==intended.ref && proposal.expectation==CareExpectation.Focused(intended.ref) && proposal.copyText==null)
                check(transport.exchanges==1 && transport.httpStatus==200 && transport.sawDone && transport.sawUsage && transport.responseClosed)
                if(slot==ProofSlot.CareInitial) initialProposal=proposal
                gate.complete(slot)
                result
            }
            inst.runOnMainSync { careBound=true; set(controller,"sample",sample); set(controller,"plan",plan); actual.dotInteraction.handle(DotIntent.Triple); actual.dotInteraction.careSession.submitGoal(GOAL) }
            fun guide(second:Boolean): Boolean {
                val state=controller.state.value; val target=state.target ?: return false; val proposal=state.proposal ?: return false
                if(state.state!=CareState.AwaitingUser || target.description!=(if(second) ForegroundApiHostFixture.TWO else ForegroundApiHostFixture.ONE)) return false
                val window=field(actual.dotInteraction,"careWindow") as? CareOverlayWindow ?: return false
                val card=field(window,"card") as? View ?: return false
                val measured=rect(card)
                val bubble=field(window.focus,"instruction") as? RectPx ?: return false
                return window.focus.bounds==target.bounds && field(window.focus,"identity")==actual.dotInteraction.careSession.instructionKey() && window.focus.view?.isAttachedToWindow==true &&
                    bubble.width>0 && bubble.height>0 && bubble.left>=measured.left && bubble.top>=measured.top && bubble.right<=measured.right && bubble.bottom<=measured.bottom &&
                    (if(second) measured.top>=target.bounds.bottom else measured.bottom<=target.bounds.top) &&
                    overlayText(actual,GOAL,card,freshCare=true) && overlayText(actual,proposal.instruction,card,freshCare=true)
            }
            try {
                await("Initial real bottom Focus guide/panel did not appear",45000) { guide(false) }
            } finally {
                runCatching { inst.runOnMainSync { // Observation must not replace the await's original failure.
                    val state=controller.state.value
                    val code=state.errorCode?.let { if(it in setOf("host_unavailable","nodes_unavailable","scene_changed",
                        "locked","accessibility_unavailable","timeout","locked_or_disconnected","permission_denied",
                        "care_timeout","care_unavailable","invalid_goal","recovering_invalid_proposal","foreground_exchange_limit")) it else "other" } ?: "none"
                    android.util.Log.i("ForegroundCareAdmission",JSONObject().put("state",state.state.name).put("code",code)
                        .put("careCalls",careCalls).put("transportCount",transports.size)
                        .put("operationOwners",field(field(CareOperationGate,"owners")!!,"count") as Int)
                        .put("automatic",field(controller,"automatic") as Boolean)
                        .put("canonicalCareBusy",canonical.careOperationBusy.value).toString())
                } }
            }
            stage("care_initial_visible",Bundle().apply {
                val target=controller.state.value.target!!; putString("targetDescription",target.description)
                putFloat("targetLeft",target.bounds.left); putFloat("targetTop",target.bounds.top); putFloat("targetRight",target.bounds.right); putFloat("targetBottom",target.bounds.bottom)
                putString("instruction",controller.state.value.proposal!!.instruction); putBoolean("actualGuiding",true); putBoolean("panelAboveTarget",true)
            })
            // No test input, ACTION_FOCUS or time-based advancement. ROOT CUA owns the native click.
            await("Actual native Focus change and Observable next proposal were not witnessed",120000) { guide(true) && controller.verifiedSteps.value.singleOrNull()?.origin==VerificationOrigin.Observable }
            check(careCalls==2 && controller.verifiedSteps.value.size==1)
            stage("care_next_visible",Bundle().apply { putInt("verifiedSteps",1); putString("origin","Observable"); putString("targetDescription",ForegroundApiHostFixture.TWO)
                val target=controller.state.value.target!!; putFloat("targetLeft",target.bounds.left); putFloat("targetTop",target.bounds.top); putFloat("targetRight",target.bounds.right); putFloat("targetBottom",target.bounds.bottom)
                putString("instruction",controller.state.value.proposal!!.instruction); putBoolean("actualGuiding",true); putBoolean("panelBelowTarget",true) })
            awaitStreamRelease(sequence,"release-care-next",60000)
            passed=true
        } finally {
            gate.fail()
            val cleanup=mutableListOf<Throwable>()
            val ownedJobs=mutableListOf<Job>()
            fun attempt(action:()->Unit) { try { action() } catch(problem:Throwable) { cleanup+=problem } }
            service?.let { actual -> attempt { inst.runOnMainSync {
                vm?.let { owned ->
                    listOf("streamJob","sendSetupJob","compactJob","compactThenSendJob").forEach { name -> (field(owned,name) as? Job)?.let(ownedJobs::add) }
                    if(owned.isStreaming.value) owned.cancelStream()
                }
                if(careBound) {
                    (field(actual.dotInteraction.careSession.controller,"work") as? Job)?.let(ownedJobs::add)
                    actual.dotInteraction.careSession.disconnect()
                    (field(actual.dotInteraction,"careWindow") as? CareOverlayWindow)?.close(); set(actual.dotInteraction,"careWindow",null)
                }
                if(chatBound) {
                    val adapter=actual.dotInteraction.javaClass.getDeclaredMethod("getAdapter").apply { isAccessible=true }.invoke(actual.dotInteraction) as DotChatAdapter
                    check(adapter.businessViewModel===vm)
                    actual.dotInteraction.close()
                    set(adapter,"session",null)
                    check(adapter.binding.value==null)
                }
                originalEnabled?.let(actual.dotOverlay::setEnabled)
            } } }
            transports.forEach { attempt { it.close(); check(it.isClosed) } }
            attempt { runBlocking { withTimeout(5000) { ownedJobs.distinct().forEach { it.join() } } } }
            service?.let { actual -> attempt { inst.runOnMainSync {
                originalSample?.let { set(actual.dotInteraction.careSession.controller,"sample",it) }
                originalPlan?.let { set(actual.dotInteraction.careSession.controller,"plan",it) }
            } } }
            id?.let { ownId ->
                attempt { await("Owned VM work did not become idle") { vm?.isStreaming?.value!=true && vm?.isCompacting?.value!=true } }
                attempt { inst.runOnMainSync { ChatViewModelStore.release(ownId); ChatViewModelStore.setActiveSession(originalActive) } }
                if(context!=null && title!=null) attempt { ownedCleanup(ownId,title!!,context!!,recordRows(ownId,title!!)) }
            }
            if(context!=null) attempt { removeOwnedPublicDirectories(ownerRoot) }
            if(::automation.isInitialized) {
                flags?.let { saved -> attempt { automation.serviceInfo=automation.serviceInfo.apply { this.flags=saved }; check(automation.serviceInfo.flags==saved) } }
                previous.forEach { (key,value) -> attempt { putSecure(key,value) } }
            }
            attempt { check(ChatViewModelStore.activeSessionId==originalActive && !handoff.exists()) }
            stage("case_finally",Bundle().apply { putBoolean("assertionsPassed",passed); putBoolean("ownedCleanupPassed",cleanup.isEmpty()); putInt("cleanupFailures",cleanup.size)
                putInt("exchanges",transports.sumOf { it.exchanges }); putBoolean("requiresRootStoppedBaselineEquality",true)
                val slots=JSONArray(); transports.forEach { transport ->
                    val slot=transport.proofSlot; val reservation=admission.getJSONArray("reservations").getJSONObject(slot.ordinal)
                    val usage=transport.usage
                    slots.put(JSONObject().put("slot",slot.name).put("number",reservation.getInt("number")).put("cap",slot.cap)
                        .put("http",transport.httpStatus).put("exchanges",transport.exchanges).put("deltas",transport.deltas)
                        .put("done",transport.sawDone).put("finish",transport.finishReason).put("usage",usage?.let {
                            JSONObject().put("freshinput",it.inputTokens).put("cachedinput",it.cacheReadInputTokens ?: 0)
                                .put("prompt",it.latestContextTokens).put("output",it.outputTokens).put("total",it.latestContextTokens+it.outputTokens)
                        } ?: JSONObject.NULL).put("ownedClosed",transport.isClosed).put("responseClosed",transport.responseClosed).put("bodyValidated",transport.bodyValidated))
                }; putString("slotResults",slots.toString())
            })
            if(cleanup.isNotEmpty()) throw IllegalStateException("Foreground proof cleanup incomplete")
        }
    }
    companion object {
        const val REQUEST="Reply with the word READY."
        const val GOAL="Focus FOREGROUND_FOCUS_ONE first, then focus FOREGROUND_FOCUS_TWO. Propose only the next Focus step with a Focused expectation."
    }
}
