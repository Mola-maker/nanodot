package io.github.nanomuse.ui.feed

import io.github.nanomuse.dot.ui.*
import androidx.compose.foundation.layout.heightIn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.theme.ChatColors
import io.github.nanomuse.feed.FeedFlow
import io.github.nanomuse.feed.FeedPost
import io.github.nanomuse.feed.FeedStore
import io.github.nanomuse.ui.home.MuseTones
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The header's sliders button and the tab live in different composables; this is the wire. */
object FeedUi {
    val settingsOpen = MutableStateFlow(false)
}

private val likeRed = Color(0xFFE0245E)

/**
 * Muse's Feed: a grey canvas, a "Wednesday morning" heading, white cards with an icon tile, a
 * title, a few lines, and a footer of heart · discuss · info. The first card explains what drives
 * the feed and lets the user edit that sentence; before the first run, the page says what to expect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTab(
    header: @Composable () -> Unit,
    onDiscuss: (FeedPost) -> Unit,
    onEditRoutine: (String) -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val posts by FeedStore.posts.collectAsState()
    val introAck by FeedFlow.introAcknowledged.collectAsState()
    val generating by FeedFlow.generating.collectAsState()
    val settingsOpen by FeedUi.settingsOpen.collectAsState()
    var infoPost by remember { mutableStateOf<FeedPost?>(null) }
    var editPrefs by remember { mutableStateOf(false) }

    // The routine needs a model; retry quietly whenever the tab shows.
    LaunchedEffect(Unit) { runCatching { FeedFlow.ensureRoutine(context) } }

    val days = remember(posts) { posts.groupBy { it.day }.toList() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MuseTones.canvas),
        contentPadding = PaddingValues(bottom = DotTokens.Space.S32),
    ) {
        item(key = "header") { header() }

        if (days.isEmpty()) {
            item(key = "today-title") { DayTitle(dayLabel(todayKey(), System.currentTimeMillis())) }
            if (!introAck) item(key = "intro") { IntroCard(onEdit = { editPrefs = true }, onAck = { FeedFlow.acknowledgeIntro(context) }) }
            item(key = "empty") { EmptyState(generating = generating, onWriteNow = { FeedFlow.generateNow(context) }, onOpenSession = onOpenSession) }
        } else {
            days.forEachIndexed { i, (day, dayPosts) ->
                item(key = "day-$day") { DayTitle(dayLabel(day, dayPosts.first().createdAt)) }
                if (i == 0 && !introAck) {
                    item(key = "intro") { IntroCard(onEdit = { editPrefs = true }, onAck = { FeedFlow.acknowledgeIntro(context) }) }
                }
                if (i == 0 && generating) item(key = "generating") { Box(Modifier.animateItem()) { GeneratingCard(onOpenSession) } }
                items(dayPosts, key = { it.id }) { post -> // the model leads with what matters most
                    PostCard(
                        post = post,
                        onLike = { FeedStore.setLiked(post, !post.liked) },
                        onDiscuss = { onDiscuss(post) },
                        onInfo = { infoPost = post },
                        modifier = Modifier.animateItem(), // new posts settle in, removed ones fade out
                    )
                }
            }
        }
    }

    if (settingsOpen || editPrefs) {
        FeedSettingsSheet(
            onDismiss = { FeedUi.settingsOpen.value = false; editPrefs = false },
            onEditRoutine = onEditRoutine,
        )
    }

    infoPost?.let { post ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { infoPost = null },
            sheetState = sheetState,
            containerColor = ChatColors.background,
        ) {
            Column(Modifier.padding(horizontal = DotTokens.Space.S24).padding(bottom = DotTokens.Space.S32)) {
                Text(post.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
                InfoRow(stringResource(R.string.nm_feed_info_type), typeLabel(post.type))
                InfoRow(stringResource(R.string.nm_feed_info_written), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(post.createdAt)))
                if (post.source.isNotEmpty()) InfoRow(stringResource(R.string.nm_feed_info_source), post.source.joinToString("\n"))
                InfoRow(stringResource(R.string.nm_feed_info_file), "minis-global/nanomuse/feed/${post.day}/${"%02d".format(Locale.ROOT, post.index)}.md")
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { FeedStore.delete(post); infoPost = null }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nm_feed_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = DotTokens.Space.S8), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 13.sp, color = ChatColors.secondaryText, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DayTitle(text: String) {
    Text(
        text = text,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = DotTokens.Space.S24, end = DotTokens.Space.S24, top = DotTokens.Space.S16, bottom = DotTokens.Space.S8),
    )
}

/** White card frame shared by every card on the page. */
@Composable
private fun FeedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MuseTones.surface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DotTokens.Space.S16, vertical = DotTokens.Space.S8)
            .animateContentSize(),
    ) { content() }
}

@Composable
private fun PostCard(
    post: FeedPost,
    onLike: () -> Unit,
    onDiscuss: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FeedCard(modifier) {
        Column(Modifier.padding(start = DotTokens.Space.S16, end = DotTokens.Space.S12, top = DotTokens.Space.S16, bottom = DotTokens.Space.S8)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MuseTones.fill, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(post.emoji.ifBlank { defaultEmoji(post.type) }, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        post.title,
                        fontSize = DotTokens.Type.Body,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    MarkdownText(
                        markdown = post.body,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = DotTokens.Type.Body, lineHeight = 22.sp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // The heart pops when it fills, the way a like should feel.
                val motionEnabled = LocalDotMotionEnabled.current
                val heartScale = remember { Animatable(1f) }
                LaunchedEffect(post.liked, motionEnabled) {
                    heartScale.snapTo(1f)
                    if (post.liked && motionEnabled) {
                        heartScale.snapTo(0.7f)
                        heartScale.animateTo(1f, tween(DotTokens.Motion.Press))
                    }
                }
                val heartTint by animateColorAsState(
                    if (post.liked) likeRed else MaterialTheme.colorScheme.onSurface,
                    tween(if (motionEnabled) DotTokens.Motion.Page else 0),
                    label = "heartTint",
                )
                FooterAction(
                    icon = if (post.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    tint = heartTint,
                    label = null,
                    onClick = onLike,
                    iconModifier = Modifier.graphicsLayer { scaleX = heartScale.value; scaleY = heartScale.value },
                )
                Spacer(Modifier.width(6.dp))
                FooterAction(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    tint = MaterialTheme.colorScheme.onSurface,
                    label = stringResource(R.string.nm_feed_discuss),
                    onClick = onDiscuss,
                )
                Spacer(Modifier.weight(1f))
                FooterAction(icon = Icons.Outlined.Info, tint = MaterialTheme.colorScheme.onSurface, label = null, onClick = onInfo)
            }
        }
    }
}

@Composable
private fun FooterAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String?,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = DotTokens.Space.S8, vertical = DotTokens.Space.S8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = iconModifier.size(22.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Muse's "About the feed" card: what drives it, the sentence itself, Edit / Got it. */
@Composable
private fun IntroCard(onEdit: () -> Unit, onAck: () -> Unit) {
    val prefs by FeedStore.preferences.collectAsState()
    FeedCard {
        Column {
            Column(Modifier.padding(horizontal = DotTokens.Space.S16, vertical = DotTokens.Space.S16)) {
                Text(stringResource(R.string.nm_feed_intro_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.nm_feed_intro_desc), fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.secondaryText)
            }
            HorizontalDivider(color = MuseTones.hairline, thickness = 1.dp)
            Column(Modifier.padding(horizontal = DotTokens.Space.S16, vertical = DotTokens.Space.S16)) {
                Text(prefs, fontSize = DotTokens.Type.Body, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(onClick = onEdit, shape = CircleShape, color = MuseTones.fill) {
                        Text(
                            stringResource(R.string.nm_feed_edit),
                            fontSize = DotTokens.Type.Body,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(onClick = onAck, shape = CircleShape, color = MuseTones.action) {
                        Text(
                            stringResource(R.string.nm_feed_got_it),
                            fontSize = DotTokens.Type.Body,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(generating: Boolean, onWriteNow: () -> Unit, onOpenSession: (String) -> Unit) {
    val context = LocalContext.current
    val task = remember { FeedFlow.task(context) }
    val time = task?.let { "%02d:%02d".format(it.timeOfDayHour, it.timeOfDayMinute) } ?: "%02d:%02d".format(FeedFlow.DEFAULT_HOUR, FeedFlow.DEFAULT_MINUTE)
    StaticCard("🖼️", stringResource(R.string.nm_feed_empty_title), stringResource(R.string.nm_feed_empty_body, time))
    StaticCard("📝", stringResource(R.string.nm_feed_empty_how_title), stringResource(R.string.nm_feed_empty_how_body))
    FeedCard {
        Column(Modifier.padding(DotTokens.Space.S16), horizontalAlignment = Alignment.CenterHorizontally) {
            if (generating) {
                FeedGenerationStatus(onOpenSession)
            } else {
                Button(
                    onClick = onWriteNow,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget),
                ) { Text(stringResource(R.string.nm_feed_write_now), fontSize = DotTokens.Type.Body, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun GeneratingCard(onOpenSession: (String) -> Unit) {
    FeedCard { FeedGenerationStatus(onOpenSession) }
}

@Composable
private fun FeedGenerationStatus(onOpenSession: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(DotTokens.Space.S16)) {
        // This queue also carries scheduled work; there is no owned per-run cancel handle.
        DotStatusSurface(DotPageState.Loading, {}) {}
        Text(stringResource(R.string.nm_feed_generating), style = MaterialTheme.typography.bodyLarge)
        FeedFlow.sessionId(context)?.let { id ->
            TextButton(onClick = { onOpenSession(id) }, modifier = Modifier.heightIn(min = DotTokens.TouchTarget)) {
                Text(stringResource(R.string.nm_library_open_chat))
            }
        }
    }
}

@Composable
private fun StaticCard(emoji: String, title: String, body: String) {
    FeedCard {
        Row(Modifier.padding(DotTokens.Space.S16), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).background(MuseTones.fill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = DotTokens.Type.Body, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = DotTokens.Type.Body, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f))
            }
        }
    }
}

/** Sliders sheet: the steering sentence, the routine's switch and time, "Write it now". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSettingsSheet(onDismiss: () -> Unit, onEditRoutine: (String) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs by FeedStore.preferences.collectAsState()
    var text by remember(prefs) { mutableStateOf(prefs) }
    val taskStore = remember { ScheduledTaskStore(context) }
    val tasks by taskStore.observe().collectAsState(initial = taskStore.all())
    val task = remember(tasks) { FeedFlow.taskId(context)?.let { id -> tasks.firstOrNull { it.id == id } } }
    val generating by FeedFlow.generating.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = ChatColors.background) {
        Column(Modifier.padding(horizontal = DotTokens.Space.S24).padding(bottom = DotTokens.Space.S24)) {
            Text(stringResource(R.string.nm_feed_settings_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.nm_feed_intro_desc), fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.secondaryText)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MuseTones.action,
                    unfocusedBorderColor = MuseTones.hairline,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = DotTokens.Type.Body, lineHeight = 22.sp),
            )
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MuseTones.fill, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(Modifier.padding(horizontal = DotTokens.Space.S16, vertical = DotTokens.Space.S8), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.nm_feed_routine_switch), fontSize = DotTokens.Type.Body, color = MaterialTheme.colorScheme.onSurface)
                            val time = task?.let { "%02d:%02d".format(it.timeOfDayHour, it.timeOfDayMinute) }
                            Text(
                                if (time != null) stringResource(R.string.nm_feed_routine_time, time) else stringResource(R.string.nm_feed_routine_needs_model),
                                fontSize = 12.sp,
                                color = ChatColors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = task != null) { task?.let { onEditRoutine(it.id) } },
                            )
                        }
                        Switch(checked = task?.enabled == true, enabled = task != null, onCheckedChange = { FeedFlow.setEnabled(context, it) })
                    }
                    HorizontalDivider(color = MuseTones.hairline, modifier = Modifier.padding(horizontal = DotTokens.Space.S16))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !generating) { FeedFlow.generateNow(context); onDismiss() }
                            .padding(horizontal = DotTokens.Space.S16, vertical = DotTokens.Space.S16),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (generating) R.string.nm_feed_generating else R.string.nm_feed_write_now),
                            fontSize = DotTokens.Type.Body,
                            color = if (generating) ChatColors.secondaryText else MuseTones.action,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { FeedStore.savePreferences(text); FeedFlow.acknowledgeIntro(context); onDismiss() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = DotTokens.TouchTarget),
            ) { Text(stringResource(R.string.nm_feed_save), fontSize = DotTokens.Type.Body, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── labels ─────────────────────────────────────────────────────────────────

private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** "Wednesday morning" for today, "Yesterday" for the day before, "Monday · Sep 22" further back. */
@Composable
private fun dayLabel(day: String, firstPostAt: Long): String {
    val weekdays = stringArrayResource(R.array.nm_weekdays)
    val cal = Calendar.getInstance()
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) }.getOrNull() ?: Date(firstPostAt)
    cal.time = date
    val weekday = weekdays[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] // Calendar: SUNDAY=1 → index 6
    val today = todayKey()
    return when {
        day == today -> {
            val hour = Calendar.getInstance().apply { timeInMillis = firstPostAt }.get(Calendar.HOUR_OF_DAY)
            val part = stringResource(
                when {
                    hour < 12 -> R.string.nm_feed_part_morning
                    hour < 18 -> R.string.nm_feed_part_afternoon
                    else -> R.string.nm_feed_part_evening
                },
            )
            stringResource(R.string.nm_feed_day_today, weekday, part)
        }
        isYesterday(cal) -> stringResource(R.string.nm_time_yesterday)
        else -> stringResource(R.string.nm_feed_day_past, weekday, DateFormat.getDateInstance(DateFormat.MEDIUM).format(date))
    }
}

private fun isYesterday(cal: Calendar): Boolean {
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return y.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && y.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun typeLabel(type: String): String = stringResource(
    when (type) {
        "brief" -> R.string.nm_feed_type_brief
        "reminder" -> R.string.nm_feed_type_reminder
        "idea" -> R.string.nm_feed_type_idea
        "goal" -> R.string.nm_feed_type_goal
        "memory" -> R.string.nm_feed_type_memory
        else -> R.string.nm_feed_type_note
    },
)

private fun defaultEmoji(type: String): String = when (type) {
    "brief" -> "📰"
    "reminder" -> "⏰"
    "idea" -> "💡"
    "goal" -> "🎯"
    "memory" -> "🧠"
    else -> "📝"
}
