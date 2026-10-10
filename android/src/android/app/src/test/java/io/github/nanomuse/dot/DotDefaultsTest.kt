package io.github.nanomuse.dot

import org.junit.Assert.*
import org.junit.Test

class DotDefaultsTest {
    private class MemoryStore : DotPreferenceStore {
        val values = mutableMapOf<String, Any>()
        override fun getString(key: String) = values[key] as? String
        override fun getBoolean(key: String) = values[key] as? Boolean
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    }

    @Test fun `first launch seeds offline Dot and safe preferences once`() {
        val store = MemoryStore()
        assertEquals(setOf(DotDefaults.NAME, DotDefaults.AVATAR, DotDefaults.THEME,
            DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED, DotDefaults.CONTINUOUS_VOICE),
            DotDefaults.seedIfMissing(store).seededKeys)
        assertEquals("Dot", store.getString(DotDefaults.NAME))
        assertEquals("dot-v1", store.getString(DotDefaults.AVATAR))
        assertEquals("system", store.getString(DotDefaults.THEME))
        assertEquals(true, store.getBoolean(DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED))
        assertEquals(false, store.getBoolean(DotDefaults.CONTINUOUS_VOICE))
        assertTrue(DotDefaults.seedIfMissing(store).seededKeys.isEmpty())
    }

    @Test fun `existing custom identity avatar theme and explicit opt out survive`() {
        val store = MemoryStore()
        store.putString(DotDefaults.NAME, "Rae")
        store.putString(DotDefaults.AVATAR, "custom")
        store.putString(DotDefaults.THEME, "dark")
        store.putBoolean(DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED, false)
        store.putBoolean(DotDefaults.CONTINUOUS_VOICE, true)
        val before = store.values.toMap()
        assertTrue(DotDefaults.seedIfMissing(store).seededKeys.isEmpty())
        assertEquals(before, store.values)
    }

    @Test fun `partial configuration fills only absent fields and preserves empty name`() {
        val store = MemoryStore()
        store.putString(DotDefaults.NAME, "")
        store.putBoolean(DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED, false)
        assertEquals(setOf(DotDefaults.AVATAR, DotDefaults.THEME, DotDefaults.CONTINUOUS_VOICE),
            DotDefaults.seedIfMissing(store).seededKeys)
        assertEquals("", store.getString(DotDefaults.NAME))
        assertEquals(false, store.getBoolean(DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED))
    }
}
