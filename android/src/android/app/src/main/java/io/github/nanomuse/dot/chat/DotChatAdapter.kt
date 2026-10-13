package io.github.nanomuse.dot.chat

import io.github.nanomuse.dot.voice.proof.VoiceProofTap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModelProvider
import com.openminis.app.MinisApp
import com.openminis.app.data.model.declaresImageInput
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.StreamingDelta
import com.openminis.app.ui.chat.removeAttachment
import io.github.nanomuse.dot.screen.ScreenSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Local preview ownership ends on Send; historical message media belongs to ChatViewModel. */
data class DotPreview(val file: File, val capturedAtMs: Long, val textOnly: Boolean,
    val regionText: File? = null)
data class DotOwnedAttachment(val id: String, val file: File)
interface DotChatSession {
    val messages: StateFlow<List<ChatMessage>>
    val inputText: StateFlow<String>
    val isStreaming: StateFlow<Boolean>
    val error: StateFlow<String?>
    val configured: StateFlow<String?>
    val deltas: StateFlow<Map<String, StreamingDelta>>
    val consumedAttachments: StateFlow<Map<String,String>>
    fun hasAttachment(id: String): Boolean = true
    fun input(text: String)
    fun stage(file: File): DotOwnedAttachment?
    fun remove(id: String)
    fun send(text: String)
    fun cancel()
    fun retry()
    fun supportsImages(): Boolean = false
}

/** Only admits local attachments and delegates business operations to the canonical VM. */
class DotChatAdapter(
    private val canonical: (String) -> String,
    private val sessionFactory: (String) -> DotChatSession,
    private val stageSnapshot: (ScreenSnapshot) -> File,
) {
    private val gate = DotSessionBinding(canonical)
    val binding = MutableStateFlow<SessionBinding?>(null)
    val preview = MutableStateFlow<DotPreview?>(null)
    val captureError = MutableStateFlow(false)
    var session: DotChatSession? = null
        private set
    val businessViewModel: ChatViewModel? get() = (session as? CanonicalSession)?.vm
    private data class Owned(val attachment: DotOwnedAttachment)
    private var owned: Owned? = null

    fun bind(sessionId: String): SessionBinding {
        val next = gate.bind(sessionId)
        if (binding.value != next) {
            voice?.cancel(); closeSpeech()
            // A switch revokes canonical work even when the send has no screenshot.
            session?.cancel()
            discardOwned(cancelPending = false)
            removePreview()
            session = sessionFactory(canonical(sessionId))
            captureError.value = false
            binding.value = next
        }
        return next
    }

    val speaker = MutableStateFlow<com.openminis.app.speech.ReadAloudPlayer?>(null)
    fun readReply(context: android.content.Context, text: String) {
        if (com.openminis.app.speech.SpeechRecognitionManager.occupied()) return
        val player = speaker.value ?: com.openminis.app.speech.ReadAloudPlayer(context).also { speaker.value = it }
        if (VoiceProofTap.active)
            VoiceProofTap.reply(this, player, text)
        player.speak(text, explicit = true)
    }
    fun stopReply() { speaker.value?.stop() }
    fun closeSpeech() {
        val player = speaker.value ?: return
        speaker.value = null
        player.stop()
        // Scope survives the overlay so cancellation is not mistaken for resource completion.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { player.shutdownAndJoin() }
            catch (_: Exception) { player.playbackFailed.value = true }
            finally { cancel() }
        }
    }
    var voice: io.github.nanomuse.dot.voice.DotVoiceController? = null
    fun voiceInput(binding: SessionBinding): String = if (gate.accepts(binding)) session?.inputText?.value.orEmpty() else ""
    fun writeVoiceInput(binding: SessionBinding, text: String): Boolean {
        if (!gate.accepts(binding)) return false
        session?.input(text) ?: return false
        return true
    }
    fun editInput(text: String) { voice?.cancel(); session?.input(text) }

    fun accepts(binding: SessionBinding) = gate.accepts(binding)

    fun attachRegion(binding: SessionBinding, snapshot: ScreenSnapshot, selectedText: File): Boolean {
        var staged: File? = null
        var transferred = false
        try {
            if (!gate.accepts(binding) || snapshot.generation != binding.generation || !selectedText.isFile) return false
            staged = stageSnapshot(snapshot)
            if (!gate.accepts(binding)) return false
            removePreview()
            preview.value = DotPreview(staged, snapshot.capturedAtMs, snapshot.image == null, selectedText)
            transferred = true
            captureError.value = false
            return true
        } catch (_: Exception) {
            if (gate.accepts(binding)) captureError.value = true
            return false
        } finally {
            if (!transferred) { staged?.delete(); selectedText.delete() }
            snapshot.close()
        }
    }

    fun attach(binding: SessionBinding, snapshot: ScreenSnapshot): Boolean {
        try {
            if (!gate.accepts(binding) || snapshot.generation != binding.generation) return false
            val file = stageSnapshot(snapshot)
            if (!gate.accepts(binding)) { file.delete(); return false }
            removePreview()
            preview.value = DotPreview(file, snapshot.capturedAtMs, snapshot.image == null)
            captureError.value = false
            return true
        } catch (_: Exception) {
            if (gate.accepts(binding)) captureError.value = true
            return false
        } finally { snapshot.close() }
    }

    fun send(binding: SessionBinding, text: String): Boolean {
        if (!gate.accepts(binding)) return false
        voice?.cancel()
        val target = session ?: return false
        if (target.isStreaming.value || target.configured.value.isNullOrBlank()) return false
        releaseConsumedFiles()
        val local = preview.value
        if (text.isBlank() && local == null) return false
        try {
            var message = text
            if (local != null) {
                // One owned inbound copy at a time; repeated taps never duplicate an attachment.
                if (owned != null) return false
                // Re-evaluate at Send: model choice can change during the local pixel preview.
                val sendRegionImage = !local.textOnly && target.supportsImages()
                // Visual questions use the actual masked crop. Node text remains an explicit
                // fallback when this capture or the currently selected model cannot send pixels.
                if (!sendRegionImage) local.regionText?.let {
                    message = listOf(text, it.readText()).filter { it.isNotBlank() }.joinToString("\n\n")
                }
                if (local.regionText == null || sendRegionImage) {
                    val attachment = target.stage(local.file) ?: return false
                    owned = Owned(attachment)
                }
            }
            target.send(message)
            removePreview()
            target.input("")
            return true
        } catch (_: Exception) {
            discardOwned(cancelPending = true)
            return false
        }
    }

    fun cancel(binding: SessionBinding) {
        if (!gate.accepts(binding)) return
        voice?.cancel(); closeSpeech()
        gate.cancel(binding)
        session?.cancel()
        discardOwned(cancelPending = false)
        removePreview()
        this.binding.value = null
    }

    fun removePreview() {
        val local = preview.value
        preview.value = null
        local?.file?.delete()
        local?.regionText?.delete()
    }

    /** Only an exact durable-media receipt and its nonqueued user row transfer ownership. */
    fun releaseConsumedFiles() {
        val inbound = owned ?: return
        val consumedMessageId = session?.consumedAttachments?.value?.get(inbound.attachment.id)
        if (consumedMessageId != null && session?.messages?.value?.any {
                it.role == "user" && !it.isQueued && it.id == consumedMessageId
            } == true) {
            owned = null
            inbound.attachment.file.delete()
        } else if (session?.isStreaming?.value == false && (session?.error?.value != null || session?.hasAttachment(inbound.attachment.id) == false)) {
            owned = null
            session?.remove(inbound.attachment.id)
            inbound.attachment.file.delete()
        }
    }

    private fun discardOwned(cancelPending: Boolean) {
        releaseConsumedFiles()
        val inbound = owned ?: return
        owned = null
        if (cancelPending) session?.cancel()
        session?.remove(inbound.attachment.id)
        inbound.attachment.file.delete()
    }

    companion object {
        fun create(app: MinisApp): DotChatAdapter = DotChatAdapter(
            ChatViewModelStore::resolvePersistedId,
            { id -> CanonicalSession(ViewModelProvider(ChatViewModelStore.ownerFor(id),
                ChatViewModel.factory(id, app.chatRepository, app.providerRepository, app,
                    app.memoryRepository, app.skillRepository, app.mcpRepository))[ChatViewModel::class.java], app.providerRepository) },
            { snapshot -> stageLocal(app, snapshot) },
        )

        private fun stageLocal(app: MinisApp, snapshot: ScreenSnapshot): File {
            val directory = File(app.cacheDir, "dot_preview").apply { check(mkdirs() || isDirectory) }
            val file = File.createTempFile("screen-${snapshot.capturedAtMs}-", if (snapshot.image == null) ".txt" else ".png", directory)
            try {
                val image = snapshot.image
                if (image == null) {
                    file.writeText("Captured ${snapshot.capturedAtMs}; text only, no screenshot.\n" +
                        snapshot.nodes.filterNot { it.password }.mapNotNull { it.text ?: it.description }.distinct().joinToString("\n"))
                } else {
                    // Password pixels need the same treatment as password node text.
                    val safe = image.copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        val canvas = Canvas(safe)
                        val paint = Paint().apply { color = Color.BLACK }
                        snapshot.nodes.filter { it.password }.forEach { node ->
                            val a = snapshot.transform.screenToImage(io.github.nanomuse.dot.gesture.PointPx(node.bounds.left,node.bounds.top))
                            val b = snapshot.transform.screenToImage(io.github.nanomuse.dot.gesture.PointPx(node.bounds.right,node.bounds.bottom))
                            canvas.drawRect(a.x,a.y,b.x,b.y,paint)
                        }
                        file.outputStream().use { check(safe.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    } finally { safe.recycle() }
                }
                return file
            } catch (failure: Throwable) { file.delete(); throw failure }
        }
    }
}

private class CanonicalSession(val vm: ChatViewModel, private val providers: ProviderRepository) : DotChatSession {
    // Only explicit effective modalities admit pixels; a guessed model-family name is insufficient.
    override fun supportsImages(): Boolean {
        val id = vm.activeEntryId.value ?: return false
        return providers.config.value.modelEntries.firstOrNull { it.id == id }?.model?.declaresImageInput == true
    }
    override val messages = vm.messages
    override val inputText = vm.inputText
    override val isStreaming = vm.isStreaming
    override val error = vm.error
    override val configured: StateFlow<String?> = vm.modelName
    override val deltas = vm.streamingById
    override val consumedAttachments = vm.consumedAttachmentMessages
    override fun hasAttachment(id: String) = vm.attachments.value.any { it.id == id }
    override fun input(text: String) = vm.setInputText(text)
    override fun stage(file: File): DotOwnedAttachment? = vm.addAttachmentFromStagedShare(file)?.let {
        val path = it.uri.path ?: return@let null
        DotOwnedAttachment(it.id, File(path))
    }
    override fun remove(id: String) = vm.removeAttachment(id)
    override fun send(text: String) = vm.sendMessage(text)
    override fun cancel() {
        // Closing an idle draft must not turn its welcome message into an interrupted reply.
        if (vm.isStreaming.value) vm.cancelStream()
        if (vm.isCompacting.value || vm.pendingCompactSend.value) vm.cancelCompact()
        if (vm.showCompactBeforeSendPrompt.value) vm.cancelCompactBeforeSend()
    }
    override fun retry() = vm.retryLast()
}
