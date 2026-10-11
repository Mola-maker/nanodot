package io.github.nanomuse.apiproof

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ForegroundMemoryPreferencesTest {
    @Test fun sessionDirectoryCleanupPreservesUnexpectedPayload() {
        val root=Files.createTempDirectory("foreground-public-session").toFile()
        val id="01234567-89ab-cdef-0123-456789abcdef"
        try {
            val uploads=java.io.File(root,"minis-sessions/$id/attachments/uploads"); uploads.mkdirs()
            val unexpected=java.io.File(uploads,"unexpected"); unexpected.writeText("public payload sentinel")
            try { removeOwnedSessionDirectories(root,id); fail("unknown payload removed") } catch(_:IllegalStateException) {}
            assertEquals("public payload sentinel",unexpected.readText())
            unexpected.delete(); removeOwnedSessionDirectories(root,id)
            assertFalse(java.io.File(root,"minis-sessions/$id").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun publicDirectoryCleanupRemovesOnlyRecognizedEmptyDirectories() {
        val root=Files.createTempDirectory("foreground-public-cleanup").toFile()
        try {
            java.io.File(root,"files/minis-sessions").mkdirs(); java.io.File(root,"cache/code").mkdirs()
            removeOwnedPublicDirectories(root)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun publicDirectoryCleanupPreservesUnknownFilesBeforeAnyDeletion() {
        val root=Files.createTempDirectory("foreground-public-unknown").toFile()
        try {
            java.io.File(root,"files").mkdir(); java.io.File(root,"cache").mkdir()
            val unknown=java.io.File(root,"files/unknown"); unknown.writeText("public sentinel")
            try { removeOwnedPublicDirectories(root); fail("unknown mutation deleted") } catch(_:IllegalStateException) {}
            assertEquals("public sentinel",unknown.readText()); assertTrue(java.io.File(root,"cache").isDirectory)
        } finally { root.deleteRecursively() }
    }
    @Test fun publicDirectoryCleanupPreservesSymlinkAndExternalSentinel() {
        val root=Files.createTempDirectory("foreground-public-link").toFile()
        val external=Files.createTempDirectory("foreground-public-external").toFile()
        try {
            val sentinel=java.io.File(external,"sentinel"); sentinel.writeText("external public sentinel")
            Files.createSymbolicLink(java.io.File(root,"files").toPath(),external.toPath())
            try { removeOwnedPublicDirectories(root); fail("symlink accepted") } catch(_:IllegalStateException) {}
            assertEquals("external public sentinel",sentinel.readText())
        } finally { java.io.File(root,"files").delete(); root.deleteRecursively(); external.deleteRecursively() }
    }
    @Test fun editsAndRemovalsStayInMemoryAndDoNotMutateReturnedSets() {
        val prefs=ForegroundMemoryPreferences()
        val original=mutableSetOf("public")
        assertTrue(prefs.edit().putString("first_conversation.session","owned").putStringSet("set",original).commit())
        original.add("foreign")
        assertEquals(setOf("public"),prefs.getStringSet("set",null))
        prefs.getStringSet("set",null)!!.add("mutation")
        assertEquals(setOf("public"),prefs.getStringSet("set",null))
        assertTrue(prefs.edit().remove("first_conversation.session").commit())
        assertNull(prefs.getString("first_conversation.session",null))
        assertTrue(prefs.edit().clear().commit()); assertTrue(prefs.all.isEmpty())
    }
    @Test fun listenersSeeOnlyCommittedOwnedChanges() {
        val prefs=ForegroundMemoryPreferences(); val keys=mutableListOf<String>()
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key -> keys.add(requireNotNull(key)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val edit=prefs.edit().putBoolean("public",true)
        assertTrue(keys.isEmpty()); edit.apply()
        assertEquals(listOf("public"),keys); assertTrue(prefs.getBoolean("public",false))
        prefs.unregisterOnSharedPreferenceChangeListener(listener); prefs.edit().putBoolean("public",false).commit()
        assertEquals(listOf("public"),keys)
    }
}
