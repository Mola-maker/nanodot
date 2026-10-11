package io.github.nanomuse.dot

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow

@Composable
fun DotSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { DotPreferences(context) }
    var enabled by remember { mutableStateOf(preferences.showOnAccessibilityConnected()) }
    SettingsSection(
        header = stringResource(R.string.nm_dot_settings_title),
        footer = stringResource(R.string.nm_dot_settings_footer),
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Visibility,
            iconColor = DotColors.LightPrimary,
            title = stringResource(R.string.nm_dot_show_orb),
            checked = enabled,
            onCheckedChange = { enabled = it; preferences.setShowOnAccessibilityConnected(it) },
            showDivider = false,
        )
    }
}
