package io.github.nanomuse.dot.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.MinisTheme

/** Test-only renderer; production components and routing are built in Task 3. */
class DotTokenGalleryFixtureActivity : ComponentActivity() {
    companion object {
        @Volatile var requestedFontScale = 1f
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            fontScale = requestedFontScale
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    private val scrollToEnd = mutableStateOf(false)

    fun showBottom() { scrollToEnd.value = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val scale = intent.getFloatExtra("scale", 1f)
        val page = intent.getStringExtra("page") ?: "actions"
        setContent {
            MinisTheme(darkTheme = dark) {
                TokenGalleryFixture(page, dark, scale, scrollToEnd.value)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenGalleryFixture(page: String, dark: Boolean, scale: Float, bottom: Boolean) {
    val scrollState = rememberScrollState()
    LaunchedEffect(bottom, scrollState.maxValue) {
        if (bottom) scrollState.scrollTo(scrollState.maxValue)
    }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            val destinations = listOf(
                "Chat" to Icons.Default.ChatBubbleOutline,
                "Tasks" to Icons.Default.TaskAlt,
                "Devices" to Icons.Default.Devices,
                "Settings" to Icons.Default.Settings,
            )
            // At 200%, use two native rows so every fixture label stays visible.
            // This rendering fixture is not the production adaptive navigation.
            Column {
                destinations.chunked(if (scale >= 2f) 2 else 4).forEach { row ->
                    NavigationBar {
                        row.forEach { (title, icon) ->
                            NavigationBarItem(
                                selected = title == "Chat", onClick = {},
                                icon = { Icon(icon, contentDescription = null) },
                                label = { Text(title) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)
                .padding(DotTokens.Space.S16),
            verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8),
        ) {
            Text("Dot tokens · ${if (dark) "dark" else "light"} · ${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium)
            Text("Fixture: $page", style = MaterialTheme.typography.bodyLarge)
            when (page) {
                "actions" -> {
                    Button(onClick = {}, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                        Text("Send")
                    }
                    OutlinedButton(onClick = {}, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                        Text("Try again")
                    }
                    Button(onClick = {}, enabled = false) { Text("Unavailable") }
                    Row(horizontalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
                        IconButton(onClick = {}) { Icon(Icons.Default.Send, contentDescription = "Send message") }
                        IconButton(onClick = {}, enabled = false) {
                            Icon(Icons.Default.Send, contentDescription = "Send unavailable")
                        }
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Loading")
                    }
                    Text("Care text uses the system font", fontSize = DotTokens.Type.Care, color = colors.tertiary)
                    AssistChip(onClick = {}, label = { Text("Saved") },
                        leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) })
                    Text("Nothing here yet. Create a task.")
                    Card {
                        ListItem(headlineContent = { Text("Could not send") },
                            supportingContent = { Text("Check the connection and try again.") },
                            leadingContent = { Icon(Icons.Default.ErrorOutline, contentDescription = null) })
                        TextButton(onClick = {}) { Text("Try again") }
                    }
                }
                "inputs" -> {
                    OutlinedTextField(value = "Dot", onValueChange = {}, label = { Text("Name") })
                    OutlinedTextField(value = "", onValueChange = {}, isError = true,
                        label = { Text("Required name") }, supportingText = { Text("Enter a name") },
                        trailingIcon = { Icon(Icons.Default.ErrorOutline, contentDescription = "Error") })
                    OutlinedTextField(value = "Unavailable", onValueChange = {}, enabled = false,
                        label = { Text("Disabled input") })
                    Row {
                        Switch(checked = true, onCheckedChange = {}, modifier = Modifier.semantics { contentDescription = "Enabled switch" })
                        Switch(checked = false, onCheckedChange = {}, enabled = false, modifier = Modifier.semantics { contentDescription = "Disabled switch" })
                        Checkbox(checked = true, onCheckedChange = {}, modifier = Modifier.semantics { contentDescription = "Selected checkbox" })
                        Checkbox(checked = false, onCheckedChange = {}, enabled = false, modifier = Modifier.semantics { contentDescription = "Disabled checkbox" })
                    }
                    ExposedDropdownMenuBox(expanded = false, onExpandedChange = {}) {
                        OutlinedTextField(value = "System", onValueChange = {}, readOnly = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            label = { Text("Theme") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(false) })
                    }
                    Card { ListItem(headlineContent = { Text("Task") }, supportingContent = { Text("Ready to run") }) }
                }
                "dialog" -> AlertDialog(onDismissRequest = {},
                    title = { Text("Could not save") },
                    text = { Text("Your changes remain here. Try again when the connection returns.") },
                    icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
                    confirmButton = { TextButton(onClick = {}) { Text("Try again") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } })
                "sheet" -> ModalBottomSheet(onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.navigationBarsPadding().padding(DotTokens.Space.S16), verticalArrangement = Arrangement.spacedBy(DotTokens.Space.S8)) {
                        Text("Task details", style = MaterialTheme.typography.titleLarge)
                        Text("Nothing here yet. Add a task to begin.")
                        OutlinedButton(onClick = {}) { Text("Add task") }
                        TextButton(onClick = {}) { Text("Close") }
                    }
                }
            }
        }
    }
}
