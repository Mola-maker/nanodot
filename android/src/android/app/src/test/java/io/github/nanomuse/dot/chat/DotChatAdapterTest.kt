package io.github.nanomuse.dot.chat

import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.StreamingDelta
import io.github.nanomuse.dot.screen.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DotChatAdapterTest {
    private class Session : DotChatSession {
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val inputText = MutableStateFlow("")
        override val isStreaming = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val configured = MutableStateFlow<String?>("local fixture")
        override val deltas = MutableStateFlow<Map<String,StreamingDelta>>(emptyMap())
        var stages = 0; var sends = 0; var stops = 0
        var sentText: String? = null
        var images = false
        override fun supportsImages() = images
        var durable: File? = null
        var hasComposerAttachment = true
        val consumed = MutableStateFlow<Map<String,String>>(emptyMap())
        override val consumedAttachments = consumed
        override fun hasAttachment(id: String) = hasComposerAttachment
        override fun input(text: String) { inputText.value = text }
        override fun stage(file: File): DotOwnedAttachment {
            stages++; val copy = File.createTempFile("dot-durable", ".png")
            file.copyTo(copy, overwrite = true); durable = copy
            return DotOwnedAttachment("owned", copy)
        }
        override fun remove(id: String) { assertEquals("owned", id) }
        override fun send(text: String) { sentText = text; sends++; isStreaming.value = true }
        override fun cancel() { stops++; isStreaming.value = false }
        override fun retry() = Unit
    }
    @Test fun adapterCancelOnlyTouchesItsInjectedSession() {
        val owned = Session()
        val unrelated = Session().apply { isStreaming.value = true }
        val adapter = DotChatAdapter({ it }, { owned }, { error("No capture") })
        val binding = adapter.bind("owned")
        adapter.send(binding, "reply")
        adapter.cancel(binding)
        assertEquals(1, owned.stops)
        assertFalse(owned.isStreaming.value)
        assertEquals(0, unrelated.stops)
        assertTrue(unrelated.isStreaming.value)
    }
    @Test fun aliasPromotionKeepsCanonicalReply() {
        val aliases = mutableMapOf<String, String>()
        val session = Session()
        val adapter = DotChatAdapter({ aliases[it] ?: it }, { session }, { error("No capture") })
        val binding = adapter.bind("draft")
        adapter.send(binding, "continue")
        aliases["draft"] = "canonical"
        assertEquals(binding, adapter.bind("canonical"))
        assertTrue(session.isStreaming.value)
        assertEquals(0, session.stops)
        adapter.cancel(binding)
        assertEquals(1, session.stops)
    }
    private fun shot(generation: Long, at: Long = 1234) = ScreenSnapshot("fixture", generation, at,
        SceneKey("fixture.host", 7, 0, 1), RectPx(0f,0f,48f,48f),0,RectPx(0f,0f,0f,0f),null,
        emptyList(),null,ScreenTransform(RectPx(0f,0f,48f,48f),48,48))
    @Test fun textModelSendsSelectedNodesWhileKeepingTheLocalCropAsPreview() {
        val session=Session()
        val crop=File.createTempFile("region-crop", ".png").apply { writeText("cropped image fixture") }
        val selected=File.createTempFile("region-nodes", ".txt").apply { writeText("inside marker") }
        val adapter=DotChatAdapter({it},{session},{crop})
        val binding=adapter.bind("A")
        try {
            assertTrue(adapter.attachRegion(binding,shot(binding.generation),selected))
            assertSame(crop,adapter.preview.value!!.file)
            assertEquals(0,session.sends)
            assertTrue(adapter.send(binding,"Read the selection"))
            assertTrue("Canonical user message must include selected text without a shell/tool read",
                session.sentText!!.contains("inside marker"))
            assertTrue(session.sentText!!.startsWith("Read the selection"))
            assertEquals("Text model receives no document/image attachment",0,session.stages)
            assertFalse(crop.exists()); assertFalse(selected.exists())
            assertEquals(1,session.sends)
        } finally { adapter.cancel(binding); crop.delete(); selected.delete() }
    }
    @Test fun pixelPreviewRechecksModelCapabilityAtSendAndIncludesSelectedText() {
        // This adapter boundary owns staged files, not Android Bitmap creation; native crop tests
        // establish pixels separately. A PNG preview with textOnly=false exercises capability routing.
        for ((before, atSend) in listOf(false to false, true to true, true to false, false to true)) {
            val session = Session().apply { images = before }
            val adapter = DotChatAdapter({ it }, { session }, { error("Use the admitted preview") })
            val binding = adapter.bind("pixel")
            val crop = File.createTempFile("region-pixels", ".png").apply { writeText("masked pixel fixture") }
            val selected = File.createTempFile("region-selected", ".txt").apply { writeText("INSIDE_NODE") }
            try {
                adapter.preview.value = DotPreview(crop, 1234, false, selected)
                session.images = atSend
                assertTrue(adapter.send(binding, "Describe"))
                assertTrue("Canonical message must contain admitted text", session.sentText!!.contains("INSIDE_NODE"))
                assertEquals("Send uses current explicit image capability", if (atSend) 1 else 0, session.stages)
                if (atSend) assertEquals("masked pixel fixture", session.durable!!.readText())
                assertFalse(crop.exists()); assertFalse(selected.exists())
            } finally { adapter.cancel(binding); crop.delete(); selected.delete() }
        }
    }
    @Test fun regionCancelDeletesBothLocalFilesAndCannotSendInTheNextSession() {
        val crop=File.createTempFile("region-crop", ".png")
        val selected=File.createTempFile("region-nodes", ".txt").apply { writeText("private selected fixture") }
        val session=Session()
        val adapter=DotChatAdapter({it},{session},{crop})
        val a=adapter.bind("A")
        try {
            assertTrue(adapter.attachRegion(a,shot(a.generation),selected))
            adapter.bind("B")
            assertFalse(crop.exists()); assertFalse(selected.exists())
            assertFalse(adapter.send(a,"late")); assertEquals(0,session.sends)
        } finally { crop.delete(); selected.delete() }
    }
    @Test fun switchingWithoutScreenshotCancelsOldOwnerButAliasRebindDoesNot() {
        val aliases = mutableMapOf<String,String>()
        val sessions = mutableMapOf<String,Session>()
        val adapter = DotChatAdapter({aliases[it] ?: it},{sessions.getOrPut(it){Session()}},{error("No capture expected")})
        val a = adapter.bind("__new__A")
        assertTrue(adapter.send(a,"plain text"))
        val old = sessions.getValue("__new__A")
        aliases["__new__A"] = "A"
        assertEquals(a,adapter.bind("A"))
        assertEquals("Promotion keeps the same operation",0,old.stops)
        val b = adapter.bind("B")
        assertEquals("Switch must revoke old work even without an owned screenshot",1,old.stops)
        assertFalse(old.isStreaming.value)
        assertEquals(0,sessions.getValue("B").stops)
        adapter.cancel(a)
        assertEquals(0,sessions.getValue("B").stops)
        adapter.cancel(b)
    }
    @Test fun lateCaptureCannotAttachToChangedOrCancelledSession() {
        val sessions = mutableMapOf<String,Session>()
        var staged = 0
        val adapter = DotChatAdapter({it}, {sessions.getOrPut(it){Session()}}, {
            staged++; File.createTempFile("dot-preview", ".png")
        })
        val a = adapter.bind("A"); val b = adapter.bind("B")
        assertFalse(adapter.attach(a,shot(a.generation)))
        assertEquals(0,staged)
        adapter.cancel(b)
        assertFalse(adapter.attach(b,shot(b.generation)))
        assertFalse(adapter.send(b,"late"))
        assertEquals(0,sessions.getValue("B").sends)
    }
    @Test fun openingPreviewIsLocalUntilExplicitSendThenCancellationDeletesBothCopies() {
        val session = Session()
        lateinit var staged: File
        val adapter = DotChatAdapter({it},{session},{
            File.createTempFile("dot-preview", ".png").also { f -> f.writeText("fixture"); staged=f }
        })
        val a = adapter.bind("A")
        assertTrue(adapter.attach(a,shot(a.generation)))
        assertEquals(1234L,adapter.preview.value!!.capturedAtMs)
        assertEquals(0,session.stages); assertEquals(0,session.sends)
        assertTrue(adapter.send(a,"inspect"))
        assertEquals(1,session.stages); assertEquals(1,session.sends)
        assertFalse(staged.exists()); assertTrue(session.durable!!.exists())
        adapter.cancel(a)
        assertFalse(session.durable!!.exists())
    }
    @Test fun deletingOrSwitchingDiscardsOnlyOwnedUnsentPreview() {
        val adapter = DotChatAdapter({it},{Session()},{File.createTempFile("dot-preview", ".png")})
        val a = adapter.bind("A")
        assertTrue(adapter.attach(a,shot(a.generation)))
        val file = adapter.preview.value!!.file
        adapter.removePreview(); assertFalse(file.exists())
        assertTrue(adapter.attach(a,shot(a.generation)))
        val next = adapter.preview.value!!.file
        adapter.bind("B"); assertFalse(next.exists())
    }
    @Test fun draftPromotionReusesSessionAndConfirmedHistoryReleasesOnlyInboundCopy() {
        val aliases = mutableMapOf<String,String>()
        val session = Session()
        val adapter = DotChatAdapter({aliases[it] ?: it},{session},{File.createTempFile("dot-preview", ".png")})
        val draft = adapter.bind("__new__A")
        assertTrue(adapter.attach(draft,shot(draft.generation)))
        aliases["__new__A"] = "A"
        assertEquals(draft,adapter.bind("A")); assertSame(session,adapter.session)
        assertTrue(adapter.send(draft,"inspect"))
        session.messages.value = listOf(ChatMessage("persisted", "user", "inspect"))
        session.consumed.value = mapOf("owned" to "persisted")
        adapter.releaseConsumedFiles()
        assertFalse(session.durable!!.exists())
        assertEquals(1,session.messages.value.size)
    }
    @Test fun captureGenerationMustMatchAndStagingExceptionDoesNotReplacePreview() {
        var fail = false
        val adapter = DotChatAdapter({it},{Session()},{
            if (fail) throw IllegalStateException("fixture error")
            File.createTempFile("dot-preview", ".png")
        })
        val a = adapter.bind("A")
        assertFalse(adapter.attach(a,shot(a.generation + 1)))
        assertNull(adapter.preview.value)
        assertTrue(adapter.attach(a,shot(a.generation)))
        val existing = adapter.preview.value!!.file
        fail = true
        assertFalse(adapter.attach(a,shot(a.generation)))
        assertTrue(existing.exists())
        assertTrue(adapter.captureError.value)
        adapter.cancel(a)
        assertFalse(existing.exists())
    }
    @Test fun lateCancellationCannotStopOrDeleteNewSessionPreview() {
        val sessions = mutableMapOf<String,Session>()
        val adapter = DotChatAdapter({it},{sessions.getOrPut(it){Session()}},{File.createTempFile("dot-preview", ".png")})
        val a = adapter.bind("A")
        val b = adapter.bind("B")
        assertTrue(adapter.attach(b,shot(b.generation)))
        val local = adapter.preview.value!!.file
        adapter.cancel(a)
        assertEquals(0,sessions.getValue("B").stops)
        assertTrue(local.exists())
        assertTrue(adapter.send(b,"B"))
        adapter.cancel(b)
    }
    @Test fun errorReleasesInboundButNeverDeletesConfirmedHistoricalMedia() {
        val session = Session()
        val historical = File.createTempFile("dot-history", ".png")
        // The real file is represented in this session's canonical history, not an unrelated file.
        val history = ChatMessage("history", "user", "prior image", attachmentNames = listOf(historical.toURI().toString()))
        session.messages.value = listOf(history)
        val adapter = DotChatAdapter({it},{session},{File.createTempFile("dot-preview", ".png")})
        val a = adapter.bind("A")
        assertTrue(adapter.attach(a,shot(a.generation)))
        assertTrue(adapter.send(a,"inspect"))
        val inbound = session.durable!!
        session.isStreaming.value = false
        session.error.value = "local failure"
        adapter.releaseConsumedFiles()
        assertFalse(inbound.exists())
        assertEquals(listOf(history),session.messages.value)
        assertTrue(File(java.net.URI(session.messages.value.single().attachmentNames.single())).exists())
        historical.delete()
    }
    @Test fun queuedAndUnrelatedUserRowsCannotConsumePendingScreenshot() {
        val session = Session()
        val adapter = DotChatAdapter({it},{session},{File.createTempFile("dot-preview", ".png")})
        val a = adapter.bind("A")
        assertTrue(adapter.attach(a,shot(a.generation)))
        assertTrue(adapter.send(a,"inspect"))
        val inbound = session.durable!!
        session.messages.value = listOf(ChatMessage("queued", "user", "next", isQueued = true))
        adapter.releaseConsumedFiles()
        assertTrue("Queued publication is not screenshot persistence",inbound.exists())
        session.messages.value += ChatMessage("unrelated", "user", "another main-app send")
        adapter.releaseConsumedFiles()
        assertTrue("Unrelated user row cannot consume this attachment",inbound.exists())
        session.consumed.value = mapOf("owned" to "queued")
        adapter.releaseConsumedFiles()
        assertTrue("Receipt still requires a nonqueued persisted row",inbound.exists())
        session.consumed.value = mapOf("owned" to "screenshot-send")
        session.messages.value += ChatMessage("screenshot-send", "user", "inspect")
        adapter.releaseConsumedFiles()
        assertFalse("Exact persisted nonqueued receipt transfers ownership",inbound.exists())
    }
    @Test fun removingOwnedAttachmentInCanonicalComposerReleasesInboundCopy() {
        val session = Session()
        val adapter = DotChatAdapter({it},{session},{File.createTempFile("dot-preview", ".png")})
        val a = adapter.bind("A")
        assertTrue(adapter.attach(a,shot(a.generation)))
        assertTrue(adapter.send(a,"inspect"))
        val inbound = session.durable!!
        session.isStreaming.value = false
        session.hasComposerAttachment = false
        adapter.releaseConsumedFiles()
        assertFalse(inbound.exists())
        assertEquals(0,session.stops)
    }

}
