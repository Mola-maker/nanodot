package io.github.nanomuse.dot.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.R

/** Primary destinations. Existing navigation routes and task-tab names remain canonical. */
enum class DotHomeRoute(@StringRes val label: Int, val icon: ImageVector) {
    Chat(R.string.nm_tab_chat, Icons.AutoMirrored.Outlined.Chat),
    Tasks(R.string.nm_tab_tasks, Icons.Outlined.Checklist),
    Devices(R.string.nm_devices_title, Icons.Outlined.Devices),
    Settings(R.string.nm_drawer_settings, Icons.Outlined.Settings),
}
