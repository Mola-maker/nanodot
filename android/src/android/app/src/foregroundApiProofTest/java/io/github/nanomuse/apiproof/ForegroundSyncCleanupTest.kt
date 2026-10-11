package io.github.nanomuse.apiproof

import org.junit.Assert.*
import org.junit.Test

/** Unknown sync evidence must prevent even the first owned chat deletion. */
class ForegroundSyncCleanupTest {
    private val id = "22222222-2222-4222-8222-222222222222"
    private val messageId = "PUBLIC_FIXTURE_MESSAGE"
    private class PublicRows {
        val owners = linkedMapOf<String,String>()
        val conversations = linkedMapOf<String,String>()
        val messages = linkedMapOf<String,Pair<String,String>>()
        val chats = linkedMapOf<String,String>()
        val reads = mutableListOf<String>()
        var deletes = 0
    }
    private fun cleanup(rows:PublicRows) = withAbsentForegroundSync(
        ownerCount = { rows.reads += "owners"; rows.owners.count { it.key==id }.toLong() },
        conversationCount = { rows.reads += "conversations"; rows.conversations.count { it.key==id }.toLong() },
        messageCount = { rows.reads += "messages"; rows.messages.count { it.value.first==id || it.key==messageId }.toLong() },
    ) {
        rows.deletes++
        rows.chats.remove(id)
    }
    private fun rejected(rows:PublicRows) {
        rows.chats[id] = "PUBLIC_OWNED_CHAT"
        val owners = rows.owners.toMap(); val conversations = rows.conversations.toMap()
        val messages = rows.messages.toMap(); val chats = rows.chats.toMap()
        val result = runCatching { cleanup(rows) }
        assertTrue("Unexpected sync evidence must fail before the first delete",result.isFailure)
        assertEquals(0,rows.deletes)
        assertEquals(chats,rows.chats); assertEquals(owners,rows.owners)
        assertEquals(conversations,rows.conversations); assertEquals(messages,rows.messages)
        assertEquals(listOf("owners","conversations","messages"),rows.reads)
    }
    @Test fun unexpectedOwnerPreservesChatAndAllSyncSentinels() {
        rejected(PublicRows().apply { owners[id]="PUBLIC_UNKNOWN_OWNER" })
    }
    @Test fun unexpectedConversationMappingPreservesChatAndAllSyncSentinels() {
        rejected(PublicRows().apply { conversations[id]="PUBLIC_UNKNOWN_CID" })
    }
    @Test fun unexpectedMessageInFixtureSessionPreservesAllRows() {
        rejected(PublicRows().apply { messages["PUBLIC_UNKNOWN_MESSAGE"]=id to "PUBLIC_UNKNOWN_MID" })
    }
    @Test fun journaledMessageRemappedToAnotherSessionPreventsChatDelete() {
        rejected(PublicRows().apply { messages[messageId]="PUBLIC_OTHER_SESSION" to "PUBLIC_UNKNOWN_MID" })
    }
    @Test fun absentFixtureInventoriesAllowOnlyOwnedChatDeletion() {
        val rows=PublicRows().apply {
            chats[id]="PUBLIC_OWNED_CHAT"; chats["PUBLIC_OTHER_SESSION"]="PUBLIC_OTHER_CHAT"
            owners["PUBLIC_OTHER_SESSION"]="PUBLIC_OTHER_OWNER"
            conversations["PUBLIC_OTHER_SESSION"]="PUBLIC_OTHER_CID"
            messages["PUBLIC_OTHER_MESSAGE"]="PUBLIC_OTHER_SESSION" to "PUBLIC_OTHER_MID"
        }
        cleanup(rows)
        assertEquals(1,rows.deletes); assertFalse(rows.chats.containsKey(id))
        assertEquals(mapOf("PUBLIC_OTHER_SESSION" to "PUBLIC_OTHER_CHAT"),rows.chats)
        assertEquals(mapOf("PUBLIC_OTHER_SESSION" to "PUBLIC_OTHER_OWNER"),rows.owners)
        assertEquals(mapOf("PUBLIC_OTHER_SESSION" to "PUBLIC_OTHER_CID"),rows.conversations)
        assertEquals(mapOf("PUBLIC_OTHER_MESSAGE" to ("PUBLIC_OTHER_SESSION" to "PUBLIC_OTHER_MID")),rows.messages)
        assertEquals(listOf("owners","conversations","messages"),rows.reads)
    }
    @Test fun unreadableInventoryNeverStartsChatDeletion() {
        var deletes=0
        val result=runCatching { withAbsentForegroundSync({0},{error("PUBLIC_UNREADABLE_INVENTORY")},{0}) { deletes++ } }
        assertTrue(result.isFailure); assertEquals(0,deletes)
    }
}
