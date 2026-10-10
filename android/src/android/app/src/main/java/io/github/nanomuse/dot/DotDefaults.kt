package io.github.nanomuse.dot

/** Adapt existing identity, avatar and appearance stores; null means absent, not false/empty. */
interface DotPreferenceStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
}

data class SeedResult(val seededKeys: Set<String>)

/** First-launch defaults only. Reset remains an explicit action in the existing settings UI. */
object DotDefaults {
    const val NAME = "identity.name"
    const val AVATAR = "identity.avatar"
    const val THEME = "appearance.theme"
    const val SHOW_ON_ACCESSIBILITY_CONNECTED = "showOnAccessibilityConnected"
    const val CONTINUOUS_VOICE = "continuousVoice"

    fun seedIfMissing(store: DotPreferenceStore): SeedResult {
        val seeded = mutableSetOf<String>()
        for ((key, value) in listOf(NAME to DotBrand.AGENT_NAME,
            AVATAR to DotBrand.DEFAULT_AVATAR, THEME to DotBrand.DEFAULT_THEME)) {
            if (store.getString(key) == null) {
                store.putString(key, value)
                seeded.add(key)
            }
        }
        for ((key, value) in listOf(SHOW_ON_ACCESSIBILITY_CONNECTED to true,
            CONTINUOUS_VOICE to false)) {
            if (store.getBoolean(key) == null) {
                store.putBoolean(key, value)
                seeded.add(key)
            }
        }
        return SeedResult(seeded.toSet())
    }
}
