package io.github.nanomuse.dot.chat

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One explicitly selected recovery, pinned to root's glass-r1-placement owned-row evidence. */
@RunWith(AndroidJUnit4::class)
class DotPlacementExactOwnedRecoveryTest {
    @Test fun recoverExactGlassR1PlacementFixture() = runBlocking<Unit> {
        val id = "e48014ad-3131-4291-adc2-b47a40caf98e"
        assumeTrue("Root must explicitly opt into this exact owned fixture recovery",
            InstrumentationRegistry.getArguments().getString("dotPlacementExactRecovery") == id)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MinisApp
        val expectedSession = ChatSessionEntity(
            id = id, title = "DOT_T4_LOCAL_ONLY_$id", modelId = "DOT_T4_LOCAL_NULL_BINDING",
            createdAt = 1791812630665L, updatedAt = 1791812645475L,
            lastMessage = "Retained history 39", memoryEnabled = 0)
        val expectedMessages = listOf(
            MessageEntity(id = "064e80e8-99ed-4cd9-8a18-b548c5c58276", sessionId = id, role = "user",
                partsJson = """[{"type":"text","value":"DOT_T4_LOCAL_ONLY_e48014ad-3131-4291-adc2-b47a40caf98e"}]""",
                createdAt = 1791812630668L, sortOrder = 0),
            MessageEntity(id = "57b71a49-58d3-4c12-8540-eb676fbcdfd0", sessionId = id, role = "user",
                partsJson = """[{"type":"text","value":"Owned placement stream"}]""",
                createdAt = 1791812633236L, sortOrder = 1),
            MessageEntity(id = "5961d2f0-1cfa-4f58-bf50-bf5c0331e4fd", sessionId = id, role = "assistant",
                partsJson = """[{"type":"text","value":"Retained history 39"},{"type":"text","value":"<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"}]""",
                createdAt = 1791812645475L, sortOrder = 2))
        val prefs = app.getSharedPreferences("nanomuse", android.content.Context.MODE_PRIVATE)
        val originalFirst = prefs.getString("first_conversation.session", null) to
            prefs.getString("first_conversation.phase", null)
        val badgePrefs = app.getSharedPreferences("session_badge_store", android.content.Context.MODE_PRIVATE)
        val badgeKey = "badge_state_by_session_v2"
        fun badgeEntries(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val entries = raw.split(';').map { entry ->
                val separator = entry.indexOf('=')
                check(separator > 0 && separator < entry.lastIndex)
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
            check(entries.map { it.first }.distinct().size == entries.size)
            return entries.toMap()
        }
        lateinit var originalBadges: Map<String, List<SessionBadgeStore.SessionBadgeState>>
        lateinit var originalBadgePrefs: Map<String, *>
        lateinit var originalBadgeEntries: Map<String, String>
        instrumentation.runOnMainSync {
            check(id !in SessionActivityTracker.activeSessions.value)
            check(id !in SessionActivityTracker.presentSessions.value)
            check(ChatViewModelStore.activeSessionId != id)
            originalBadges = SessionBadgeStore.byId.value.mapValues { it.value.toList() }
            check(originalBadges[id] == null || originalBadges[id] == listOf(SessionBadgeStore.SessionBadgeState.PAUSED))
            originalBadgePrefs = badgePrefs.all.toMap()
            originalBadgeEntries = badgeEntries(badgePrefs.getString(badgeKey, null))
            if (id in originalBadges) {
                // Refuse migration/unknown entries: removal may write only this known v2
                // PAUSED entry. Exact raw tokens preserve unrelated queues and timestamps.
                val persistedQueues = originalBadgeEntries.mapValues { (_, raw) -> raw.split(',').map { token ->
                    val pieces = token.split(':')
                    check(pieces.size == 2 && pieces[1].toLongOrNull()?.let { it >= 0 } == true)
                    SessionBadgeStore.SessionBadgeState.valueOf(pieces[0])
                } }
                check(persistedQueues == originalBadges)
            } else check(id !in originalBadgeEntries)
        }
        val media = com.openminis.app.data.storage.MediaStore(app)
        val mediaDirectories = media.mediaBaseDir.walkTopDown().filter { it.isDirectory && it.name == id }.toList()
        val sessionDirectory = java.io.File(app.filesDir, "minis-sessions/$id")
        val syncDatabase = io.github.nanomuse.sync.SyncDatabase.get(app)
        val sync = syncDatabase.dao()
        // Root's known fixture baseline has no media/session files. Preserve unexpected content.
        check(mediaDirectories.all { it.listFiles()?.isEmpty() == true })
        check(!sessionDirectory.exists() || sessionDirectory.isDirectory && sessionDirectory.listFiles()?.isEmpty() == true)
        suspend fun checkExactSyncSnapshot() {
            // Root's target-only snapshot has no mappings and one signed-out owner.
            check(sync.conversation(id) == null && sync.messages(id).isEmpty())
            check(sync.owners().filter { it.sessionId == id } == listOf(io.github.nanomuse.sync.SessionOwner(id, "")))
        }
        syncDatabase.withTransaction { checkExactSyncSnapshot() }
        app.database.withTransaction {
            // Entity equality checks every column, including null/default metadata, and the
            // complete three-row set. A new/different record aborts before any deletion.
            check(app.chatRepository.getSession(id) == expectedSession) { "Exact recovery session changed; preserve it" }
            check(app.chatRepository.dao.loadMessages(id).sortedBy { it.sortOrder } == expectedMessages) {
                "Exact recovery message snapshot changed; preserve it"
            }
            app.chatRepository.dao.deleteMessages(id)
            app.chatRepository.dao.deleteSession(id)
            check(app.chatRepository.getSession(id) == null && app.chatRepository.dao.loadMessages(id).isEmpty())
        }
        syncDatabase.withTransaction {
            // Separate databases cannot share a Room transaction. Recheck sync before its
            // keyed deletion; a changed sync snapshot is preserved even after chat removal.
            checkExactSyncSnapshot()
            sync.removeOwners(listOf(id)); sync.removeConversations(listOf(id)); sync.removeMessagesOfAll(listOf(id))
            check(sync.conversation(id) == null && sync.messages(id).isEmpty() && sync.owners().none { it.sessionId == id })
        }
        mediaDirectories.forEach { check(it.delete()) }
        if (sessionDirectory.exists()) check(sessionDirectory.delete())
        instrumentation.runOnMainSync {
            ChatViewModelStore.release(id)
            check(SessionBadgeStore.byId.value == originalBadges)
            check(badgePrefs.all == originalBadgePrefs)
            SessionBadgeStore.remove(id, SessionBadgeStore.SessionBadgeState.PAUSED)
            check(SessionBadgeStore.byId.value == originalBadges - id)
            check(badgeEntries(badgePrefs.getString(badgeKey, null)) == originalBadgeEntries - id)
            check(badgePrefs.all - badgeKey == originalBadgePrefs - badgeKey)
            if (id !in originalBadges) check(badgePrefs.all == originalBadgePrefs)
        }
        check(app.chatRepository.getSession(id) == null && app.chatRepository.dao.loadMessages(id).isEmpty())
        check(sync.conversation(id) == null && sync.messages(id).isEmpty() && sync.owners().none { it.sessionId == id })
        check(media.mediaBaseDir.walkTopDown().none { it.isDirectory && it.name == id })
        check(!sessionDirectory.exists())
        check(originalFirst == (prefs.getString("first_conversation.session", null) to prefs.getString("first_conversation.phase", null)))
        println("T4_EXACT_RECOVERY id=$id knownRows=3 exactEntitySnapshot=true ownedOnly=true firstPrefsUnchanged=true unrelatedBadgesAndStampsUnchanged=true")
    }
}
