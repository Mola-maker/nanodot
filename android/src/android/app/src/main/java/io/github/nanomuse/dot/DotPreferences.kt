package io.github.nanomuse.dot

import android.content.Context
import com.openminis.app.agent.SoulStore
import com.openminis.app.ui.settings.PREF_APPEARANCE
import java.io.File

/** No parallel identity database: name is SOUL.md, avatar is its existing file, theme is an int. */
class DotPreferences(private val context: Context) : DotPreferenceStore {
    private val prefs = context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = when (key) {
        DotDefaults.NAME -> SoulStore.load(context)?.metadata?.name
        // Built-in artwork is implicit in AvatarStore; even corrupt custom files are retained.
        DotDefaults.AVATAR -> if (File(context.filesDir,
            "minis-global/nanomuse/avatar/base.png").exists()) "custom" else DotBrand.DEFAULT_AVATAR
        DotDefaults.THEME -> if (prefs.contains("theme_mode"))
            when (prefs.getInt("theme_mode", 0)) { 1 -> "light"; 2 -> "dark"; else -> "system" }
            else null
        else -> error("Unknown Dot string preference: $key")
    }

    override fun getBoolean(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    override fun putString(key: String, value: String) {
        when (key) {
            DotDefaults.NAME -> SoulStore.ensureExists(context)
            DotDefaults.AVATAR -> Unit // Missing custom file already selects the built-in Dot.
            DotDefaults.THEME -> prefs.edit().putInt("theme_mode",
                when (value) { "light" -> 1; "dark" -> 2; else -> 0 }).apply()
            else -> error("Unknown Dot string preference: $key")
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /** P2 reads this choice when accessibility connects; seeding never enables Hands. */
    fun showOnAccessibilityConnected(): Boolean =
        prefs.getBoolean(DotDefaults.SHOW_ON_ACCESSIBILITY_CONNECTED, true)

    /** P2's voice session reads the same preference exposed through minis-config. */
    fun continuousVoice(): Boolean = prefs.getBoolean(DotDefaults.CONTINUOUS_VOICE, false)
}
