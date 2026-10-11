package io.github.nanomuse.dot.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.dot.DotColors
import io.github.nanomuse.dot.gesture.DotIntent

@Composable
fun DotOrb(onIntent: (DotIntent) -> Unit) {
    val dark = isSystemInDarkTheme()
    val open = stringResource(R.string.nm_dot_open_conversation)
    val screen = stringResource(R.string.nm_dot_view_screen)
    val care = stringResource(R.string.nm_dot_start_care)
    val description = stringResource(R.string.nm_dot_orb_description)
    Box(
        Modifier.size(48.dp)
            .background(if (dark) DotColors.DarkPrimary else DotColors.LightPrimary, CircleShape)
            .border(1.dp, if (dark) DotColors.DarkOutline else DotColors.LightOutline, CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(open) { onIntent(DotIntent.Single); true }
                customActions = listOf(
                    CustomAccessibilityAction(open) { onIntent(DotIntent.Single); true },
                    CustomAccessibilityAction(screen) { onIntent(DotIntent.Double); true },
                    CustomAccessibilityAction(care) { onIntent(DotIntent.Triple); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("•", color = if (dark) DotColors.DarkOnPrimary else DotColors.LightOnPrimary,
            fontSize = 32.sp)
    }
}
