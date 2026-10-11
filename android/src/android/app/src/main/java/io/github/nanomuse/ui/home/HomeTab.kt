package io.github.nanomuse.ui.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.automirrored.outlined.Feed
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.R

/**
 * Legacy tab names remain the task subroutes and HomeBus wire vocabulary.
 */
enum class HomeTab(
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    CHAT(R.string.nm_tab_chat, Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    FEED(R.string.nm_tab_feed, Icons.AutoMirrored.Outlined.Feed, Icons.AutoMirrored.Filled.Feed),
    IDEAS(R.string.nm_tab_ideas, Icons.Outlined.Lightbulb, Icons.Filled.Lightbulb),
    GOALS(R.string.nm_tab_goals, Icons.Outlined.CheckBox, Icons.Filled.CheckBox),
    LIBRARY(R.string.nm_task_library, Icons.Outlined.Category, Icons.Filled.Category);

    val primaryRoute: io.github.nanomuse.dot.ui.DotHomeRoute
        get() = if (this == CHAT) io.github.nanomuse.dot.ui.DotHomeRoute.Chat else io.github.nanomuse.dot.ui.DotHomeRoute.Tasks

    companion object {
        val taskEntries = listOf(FEED, IDEAS, GOALS, LIBRARY)
    }
}
