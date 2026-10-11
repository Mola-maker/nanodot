package io.github.nanomuse.ui.home

import android.widget.Toast
import io.github.nanomuse.dot.ui.DotHomeShell
import io.github.nanomuse.dot.ui.DotHomeHeader
import io.github.nanomuse.dot.ui.DotHomeRoute
import io.github.nanomuse.dot.ui.DotTaskHub
import io.github.nanomuse.dot.ui.DotTokens
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.openminis.app.R
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.ui.chat.ChatScreen
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.navigation.FilePreviewHolder
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.navigation.safeNavigate
import com.openminis.app.ui.theme.ChatColors
import io.github.nanomuse.ui.chat.NmHomeChrome
import io.github.nanomuse.goals.GoalCategory
import io.github.nanomuse.goals.GoalFlow
import io.github.nanomuse.home.MainChat
import io.github.nanomuse.ideas.Idea
import io.github.nanomuse.ui.avatar.rememberAgentMood
import io.github.nanomuse.ui.feed.FeedTab
import io.github.nanomuse.ui.feed.FeedUi
import io.github.nanomuse.ui.goals.GoalsTab
import io.github.nanomuse.ui.header.rememberNanoMuseStatusLine
import io.github.nanomuse.ui.ideas.IdeasTab
import io.github.nanomuse.ui.library.LibraryTab
import io.github.nanomuse.ui.onboarding.FirstRunSetup
import io.github.nanomuse.ui.onboarding.FirstRunSetupScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Nanodot home at every window size: chat, task hub, devices and settings. Expanded windows
 * show the existing session list beside the conversation; smaller panes use the same drawer.
 * Routes.SESSION_LIST remains the canonical host, and HomeTab/HomeBus aliases stay intact.
 *
 * The chat tab stays composed while another tab is showing (hidden under it), so switching
 * tabs never rebuilds the conversation, drops the composer text or loses the scroll position.
 */
@Composable
fun NanoMuseHome(
    navController: NavHostController,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var tab by rememberSaveable { mutableStateOf(HomeTab.CHAT) }
    var taskTab by rememberSaveable { mutableStateOf(HomeTab.FEED) }
    var chatSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val mainSessionId by MainChat.sessionId.collectAsState()

    LaunchedEffect(Unit) {
        val main = MainChat.resolve(context, chatRepository)
        if (chatSessionId == null) chatSessionId = main
    }

    // contract C10: a sign-in made the open chat another account's — the home is this account's now
    val hiddenSessions by io.github.nanomuse.sync.ConversationSync.hidden.collectAsState()
    LaunchedEffect(hiddenSessions, mainSessionId) {
        val shown = chatSessionId ?: return@LaunchedEffect
        if (shown in hiddenSessions) chatSessionId = mainSessionId ?: MainChat.resolve(context, chatRepository)
    }

    val isMainChat = chatSessionId?.let { MainChat.isMain(context, it) } ?: true

    // Each time the home comes to the foreground: one more distinct day for the star asks'
    // day counter (StarPrompt), and the relay's nudges policy re-read when a day has passed.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                io.github.nanomuse.community.Nudges.refreshIfStale(context)
                io.github.nanomuse.community.StarPrompt.dayOpened(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun showSession(id: String) {
        focusManager.clearFocus(force = true)
        chatSessionId = id
        tab = HomeTab.CHAT
    }

    fun showMain() {
        val id = mainSessionId ?: return
        showSession(id)
    }

    // Requests from cards inside messages, idea sheets, etc.
    var pendingPrefill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        HomeBus.requests.collect { req ->
            when (req) {
                is HomeBus.Request.ShowTab -> {
                    tab = req.tab
                    if (req.tab != HomeTab.CHAT) taskTab = req.tab
                }
                is HomeBus.Request.ShowSession -> showSession(req.sessionId)
                is HomeBus.Request.PrefillComposer -> {
                    showMain()
                    pendingPrefill = req.text
                }
            }
            HomeBus.handled()
        }
    }

    // First run. Until the app has a model to talk to, the home is Muse's welcome screen with
    // the three steps (provider → its models → the first conversation), not a chat that cannot
    // answer. Gated on the provider config and the session list having loaded, so a returning
    // user never sees the setup flash past before their providers are read.
    val providerConfig by providerRepository.config.collectAsState()
    val configLoaded by providerRepository.configLoaded.collectAsState()
    val sessions by chatRepository.observeSessions().collectAsState(initial = null)
    // contract C12: the account left and took the open chat with it (or the chat was deleted
    // under the home) — the home shows this account's main chat, resolved afresh if need be
    LaunchedEffect(sessions, mainSessionId) {
        val shown = chatSessionId ?: return@LaunchedEffect
        val list = sessions ?: return@LaunchedEffect
        if (MainChat.isDraftId(shown) || list.any { it.id == shown }) return@LaunchedEffect
        val main = mainSessionId
        chatSessionId = if (main != null && main != shown && (MainChat.isDraftId(main) || list.any { it.id == main })) {
            main
        } else {
            MainChat.reset()
            MainChat.resolve(context, chatRepository)
        }
    }
    var setupDone by remember { mutableStateOf(FirstRunSetup.isDone(context)) }
    // A provider of the person's own that could answer: enabled and holding a credential. The
    // Cloud instance is the account, counted through `signedIn` below.
    val cloudId = io.github.nanomuse.cloud.NanoMuseCloud.instance(context)?.id
    val hasProviders = remember(providerConfig) {
        providerConfig.instances.any { it.id != cloudId && it.isEnabled && providerRepository.hasAnyCredential(it) }
    }
    val hasGroups = providerConfig.modelGroups.isNotEmpty()
    // The flow flips when the sign-in lands (or the key is revoked); signed out with a key of
    // one's own the chat stays, the sign-in is an invitation under Settings.
    val signedIn by io.github.nanomuse.cloud.NanoMuseCloud.signedIn(context).collectAsState()
    val phase = when {
        !configLoaded || sessions == null || signedIn == null -> HomePhase.LOADING
        FirstRunSetup.needed(signedIn == true, hasProviders, sessions!!.isNotEmpty(), setupDone) -> HomePhase.SETUP
        else -> HomePhase.HOME
    }
    // Once the chat has been shown the setup is over for good: an empty main chat is a draft
    // with no session row, so without this a trip to the profile page and back could bring
    // the welcome screen up again.
    LaunchedEffect(phase) {
        if (phase == HomePhase.HOME && !setupDone) {
            FirstRunSetup.markDone(context)
            setupDone = true
        }
    }

    // The main chat's ViewModel: the same instance ChatScreen uses (process-level store), so the
    // tab headers can show its mood/status and the tabs can send into it. Not created before the
    // setup is over: a ViewModel resolves its model when it is built, and one built while the
    // default group did not exist yet would keep talking to whatever entry it found first.
    val mainVm: ChatViewModel? = if (phase != HomePhase.HOME) null else mainSessionId?.let { id ->
        viewModel(
            viewModelStoreOwner = ChatViewModelStore.ownerFor(id),
            factory = ChatViewModel.factory(
                sessionId = id,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                appContext = context.applicationContext,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            ),
        )
    }
    // The profile page's "Change avatar": the request pre-typed, keyboard up, once the main
    // chat's ViewModel is there to take it.
    LaunchedEffect(pendingPrefill, mainVm) {
        val text = pendingPrefill ?: return@LaunchedEffect
        val vm = mainVm ?: return@LaunchedEffect
        pendingPrefill = null
        vm.nmPrefillComposer(text)
    }
    // Status follows the shown canonical session, using ChatScreen's existing process owner.
    // The main VM above remains the target for Feed/Ideas/Goals actions.
    val shownId = chatSessionId?.let { ChatViewModelStore.rememberPersistedId(it) }
    val statusVm: ChatViewModel? = if (phase != HomePhase.HOME) null else shownId?.let { id ->
        viewModel(
            viewModelStoreOwner = ChatViewModelStore.ownerFor(id),
            factory = ChatViewModel.factory(
                sessionId = id, chatRepository = chatRepository, providerRepository = providerRepository,
                appContext = context.applicationContext, memoryRepository = memoryRepository,
                skillRepository = skillRepository, mcpRepository = mcpRepository,
            ),
        )
    }
    val streaming by (statusVm?.isStreaming ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val error by (statusVm?.error ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val mood = rememberAgentMood(streaming, error)
    // The same account of the work the chat header gives: the step, the reply arriving, or the request it is on.
    val replying by remember(statusVm) {
        statusVm?.streamingById?.let { flow ->
            flow.map { stream ->
                stream.values.any { d -> !d.isAwaitingModelResponse && d.toolBlocks.lastOrNull()?.let { it.kind == "text" && it.content.isNotEmpty() } == true }
            }.distinctUntilChanged()
        } ?: kotlinx.coroutines.flow.flowOf(false)
    }.collectAsState(initial = false)
    val homeMessages by (statusVm?.uiMessages ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.openminis.app.ui.chat.ChatMessage>()) }).collectAsState()
    val requestBrief = remember(homeMessages) { io.github.nanomuse.ui.header.requestBrief(homeMessages.lastOrNull { it.role == "user" }?.content) }
    val statusLine = rememberNanoMuseStatusLine(streaming, mood, replying = replying, request = requestBrief)
    val soul by SoulStore.cachedMetadata.collectAsState()
    val agentName = soul.name.trim().ifEmpty { stringResource(R.string.app_name) }

    fun sendToMainChat(text: String) {
        val vm = mainVm ?: run {
            Toast.makeText(context, R.string.nm_home_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        showMain()
        vm.nmMarkPersonTurn() // an idea or goal card the person tapped: a task for StarPrompt's count
        vm.sendMessage(text)
    }

    fun startGoal(category: GoalCategory, seed: String? = null) {
        val id = mainSessionId ?: return
        val opener = GoalFlow.startCreation(context, id, category)
        sendToMainChat(if (seed.isNullOrBlank()) opener else "$opener $seed")
    }

    fun createRoutine(idea: Idea) {
        val (h, m) = idea.time?.split(":")?.takeIf { it.size == 2 }
            ?.let { (a, b) -> a.toIntOrNull()?.coerceIn(0, 23) to b.toIntOrNull()?.coerceIn(0, 59) }
            ?.takeIf { it.first != null && it.second != null }
            ?.let { it.first!! to it.second!! }
            ?: (9 to 0)
        val task = ScheduledTaskManager(context).create(
            ScheduledTask(
                label = idea.title.take(40),
                timeOfDayHour = h,
                timeOfDayMinute = m,
                repeatMode = ScheduledRepeatMode.DAILY,
                prompt = idea.prompt,
            ),
        )
        Toast.makeText(context, R.string.nm_idea_routine_created, Toast.LENGTH_SHORT).show()
        navController.safeNavigate(Routes.scheduledTaskEdit(task.id))
    }

    /** "Discuss" on a feed card: a side chat that opens on the post. */
    fun discussPost(post: io.github.nanomuse.feed.FeedPost) {
        scope.launch {
            val app = context.applicationContext as? com.openminis.app.MinisApp ?: return@launch
            val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.nanomuse.goals.GoalSessions.create(app, post.title.take(40))
            } ?: run {
                Toast.makeText(context, R.string.nm_feed_routine_needs_model, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showSession(id)
            val opener = context.getString(R.string.nm_feed_discuss_opener, post.title, post.body.take(1200))
            com.openminis.app.debug.HeadlessChatRunner.prompt(
                context = app, sessionId = id, text = opener, wait = false, timeoutMs = 10 * 60 * 1000L,
            )
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    fun openDrawer() {
        focusManager.clearFocus(force = true)
        scope.launch { drawerState.open() }
    }
    fun closeDrawer() = scope.launch { drawerState.close() }

    // Back: close the drawer → leave a non-chat tab → leave a side chat → (system) leave the app.
    val homeShown = phase != HomePhase.LOADING
    BackHandler(enabled = homeShown && drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = homeShown && !drawerState.isOpen && tab != HomeTab.CHAT) { tab = HomeTab.CHAT }
    BackHandler(enabled = homeShown && !drawerState.isOpen && tab == HomeTab.CHAT && !isMainChat && mainSessionId != null) { showMain() }

    // The drawer + tab shell, as a local composable so it can share every piece of state above
    // and still be one branch of the phase switch below.
    val sessionList: @Composable () -> Unit = {
                SideChatDrawer(
                    agentName = agentName,
                    chatRepository = chatRepository,
                    mainSessionId = mainSessionId?.let { ChatViewModelStore.rememberPersistedId(it) },
                    currentSessionId = chatSessionId?.let { ChatViewModelStore.rememberPersistedId(it) },
                    onOpenMain = { closeDrawer(); showMain() },
                    onOpenSession = { id -> closeDrawer(); showSession(id) },
                    onNewChat = { closeDrawer(); showSession("__new__${UUID.randomUUID()}") },
                    onAllChats = { closeDrawer(); navController.safeNavigate(ROUTE_ALL_CHATS) },
                    onSettings = { closeDrawer(); navController.safeNavigate(Routes.SETTINGS) },
                    onSetMain = { id -> MainChat.set(context, id); closeDrawer(); showSession(id) },
                    onSystemFiles = { closeDrawer(); navController.safeNavigate(io.github.nanomuse.ui.sysfiles.ROUTE_SYSTEM_FILES) },
                    onDevices = { closeDrawer(); navController.safeNavigate(io.github.nanomuse.ui.cloud.ROUTE_CLOUD_ACCOUNT) },
                    onCoding = { closeDrawer(); navController.safeNavigate(io.github.nanomuse.ui.coding.ROUTE_CODING) },
                )
    }

    val setupScreen: @Composable () -> Unit = {
        FirstRunSetupScreen(
            agentName = agentName, signedIn = signedIn == true, hasGroups = hasGroups, hasProviders = hasProviders,
            onSignIn = { navController.safeNavigate(io.github.nanomuse.ui.cloud.ROUTE_CLOUD_SIGN_IN) },
            onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
            onSelectModels = { navController.safeNavigate(Routes.ONBOARDING_MODELS) },
            onStart = { FirstRunSetup.markDone(context); setupDone = true },
            onSettings = { navController.safeNavigate(Routes.SETTINGS) },
        )
    }

    val homeShell: @Composable () -> Unit = {
    val motionEnabled = io.github.nanomuse.dot.ui.LocalDotMotionEnabled.current
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || tab == HomeTab.CHAT,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MuseTones.surface,
                drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            ) {
                sessionList()
            }
        },
    ) {
        DotHomeShell(
            route = tab.primaryRoute,
            onRoute = { picked ->
                focusManager.clearFocus(force = true)
                when (picked) {
                    DotHomeRoute.Chat -> if (tab == HomeTab.CHAT && !isMainChat) showMain() else tab = HomeTab.CHAT
                    DotHomeRoute.Tasks -> tab = taskTab
                    DotHomeRoute.Devices -> navController.safeNavigate(io.github.nanomuse.ui.cloud.ROUTE_CLOUD_ACCOUNT)
                    DotHomeRoute.Settings -> navController.safeNavigate(Routes.SETTINGS)
                }
            },
            conversationList = sessionList,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Chat tab — always composed, hidden while another tab is on top.
                val chatVisible = tab == HomeTab.CHAT
                // Fades a touch slower than the page above it fades in, so the switch reads as
                // one cross-fade rather than a cut to the canvas.
                val chatAlpha by animateFloatAsState(
                    targetValue = if (chatVisible) 1f else 0f,
                    animationSpec = tween(if (motionEnabled) DotTokens.Motion.Page else 0),
                    label = "chatAlpha",
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .graphicsLayer { alpha = chatAlpha }
                        .then(if (chatVisible) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    val sid = chatSessionId
                    if (phase == HomePhase.SETUP) {
                        setupScreen()
                    } else if (sid != null) {
                        ChatScreen(
                            sessionId = sid,
                            chatRepository = chatRepository,
                            providerRepository = providerRepository,
                            memoryRepository = memoryRepository,
                            skillRepository = skillRepository,
                            mcpRepository = mcpRepository,
                            onBack = { if (!isMainChat) showMain() },
                            onNewChat = { showSession("__new__${UUID.randomUUID()}") },
                            onOpenTerminal = { navController.safeNavigate(Routes.terminal(sessionId = sid)) },
                            onOpenTerminalWithCommand = { command ->
                                navController.safeNavigate(Routes.terminal(initCommand = command, sessionId = sid))
                            },
                            onMoveToSession = { targetId -> showSession(targetId) },
                            onBrowseChatFiles = { navController.safeNavigate(Routes.chatFiles(sid)) },
                            onPreviewAttachment = { item ->
                                FilePreviewHolder.currentItem = item
                                navController.safeNavigate(Routes.FILE_PREVIEW)
                            },
                            onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                            nmHome = NmHomeChrome(
                                isMainChat = isMainChat, onOpenDrawer = { openDrawer() },
                                title = {
                                    androidx.compose.material3.TextButton(
                                        onClick = { navController.safeNavigate(io.github.nanomuse.ui.profile.ROUTE_AGENT_PROFILE) },
                                    ) {
                                        androidx.compose.foundation.layout.Column {
                                            Text(agentName, style = MaterialTheme.typography.titleMedium)
                                            if (!statusLine.isNullOrBlank()) Text(statusLine,
                                                style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                    }
                                },
                            ),
                        )
                    }
                }

                val holder = rememberSaveableStateHolder()
                // The page that is (or was last) on top of the chat. Keeping it while the
                // sheet fades back to the chat means the content does not blink to empty.
                var pageTab by rememberSaveable { mutableStateOf(taskTab) }
                if (tab != HomeTab.CHAT) pageTab = tab
                AnimatedVisibility(
                    visible = !chatVisible,
                    enter = fadeIn(tween(if (motionEnabled) DotTokens.Motion.Page else 0)),
                    exit = fadeOut(tween(if (motionEnabled) DotTokens.Motion.Page else 0)),
                    modifier = Modifier.fillMaxSize().zIndex(1f),
                ) {
                    Surface(
                        color = ChatColors.background,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        DotTaskHub(
                            selected = pageTab,
                            onSelect = { taskTab = it; tab = it },
                            header = {
                                TabHeader(tab = pageTab, name = agentName, statusLine = statusLine,
                                    onNameClick = { navController.safeNavigate(io.github.nanomuse.ui.profile.ROUTE_AGENT_PROFILE) },
                                    onOpenDrawer = { openDrawer() },
                                    onNewChat = { showSession("__new__${UUID.randomUUID()}") },
                                    navController = navController, mainSessionId = mainSessionId)
                            },
                        ) {
                        AnimatedContent(
                            targetState = pageTab,
                            transitionSpec = {
                                (fadeIn(tween(if (motionEnabled) DotTokens.Motion.Page else 0)) togetherWith fadeOut(tween(if (motionEnabled) DotTokens.Motion.Page else 0)))
                                    .using(SizeTransform(clip = false))
                            },
                            label = "nmTab",
                        ) { page ->
                        holder.SaveableStateProvider(page.name) {
                            val header: @Composable () -> Unit = {}
                            when (page) {
                                HomeTab.FEED -> FeedTab(
                                    header = header,
                                    onOpenSession = { showSession(it) },
                                    onDiscuss = { discussPost(it) },
                                    onEditRoutine = { navController.safeNavigate(Routes.scheduledTaskEdit(it)) },
                                )
                                HomeTab.IDEAS -> IdeasTab(
                                    header = header,
                                    onSendToChat = { sendToMainChat(it) },
                                    onCreateRoutine = { createRoutine(it) },
                                    onStartGoal = { category, seed -> startGoal(category, seed) },
                                )
                                HomeTab.GOALS -> GoalsTab(
                                    header = header,
                                    onStartGoal = { startGoal(it) },
                                    onOpenSession = { showSession(it) },
                                    onEditRoutine = { navController.safeNavigate(Routes.scheduledTaskEdit(it)) },
                                    onRoutineRuns = { navController.safeNavigate(Routes.scheduledTaskRuns(it)) },
                                    onAllRoutines = { navController.safeNavigate(Routes.SCHEDULED_TASKS) },
                                )
                                HomeTab.LIBRARY -> LibraryTab(
                                    header = header,
                                    chatRepository = chatRepository,
                                    onPreview = { item ->
                                        FilePreviewHolder.currentItem = item
                                        navController.safeNavigate(Routes.FILE_PREVIEW)
                                    },
                                    onOpenSession = { showSession(it) },
                                )
                                HomeTab.CHAT -> Unit
                            }
                        }
                        }
                        }
                    }
                }
            }
        }
    }
    } // homeShell

    if (phase == HomePhase.LOADING) {
        Surface(color = MuseTones.surface, modifier = Modifier.fillMaxSize()) {}
    } else {
        // Model setup is still the Chat panel. All primary paths stay available without credentials.
        homeShell()
    }

}

private enum class HomePhase { LOADING, SETUP, HOME }

/** Compact task header, retaining every existing context-menu action. */
@Composable
private fun TabHeader(
    tab: HomeTab,
    name: String,
    statusLine: String?,
    onNameClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    navController: NavHostController,
    mainSessionId: String?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        DotHomeHeader(name, statusLine, onNameClick, onOpenDrawer, onNewChat,
            onMore = { if (tab == HomeTab.FEED) FeedUi.settingsOpen.value = true else menu = true }) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (tab == HomeTab.CHAT) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nm_coding_title)) },
                            onClick = { menu = false; navController.safeNavigate(io.github.nanomuse.ui.coding.ROUTE_CODING) },
                        )
                    }
                    when (tab) {
                        HomeTab.GOALS -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nm_routine_all)) },
                                onClick = { menu = false; navController.safeNavigate(Routes.SCHEDULED_TASKS) },
                            )
                        }
                        HomeTab.LIBRARY -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nm_library_menu_shared_folders)) },
                                onClick = { menu = false; navController.safeNavigate(Routes.SHARED_FOLDERS) },
                            )
                            if (mainSessionId != null && !MainChat.isDraftId(mainSessionId)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                                    onClick = { menu = false; navController.safeNavigate(Routes.chatFiles(mainSessionId)) },
                                )
                            }
                        }
                        else -> Unit
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nm_sysfiles_title)) },
                        onClick = { menu = false; navController.safeNavigate(io.github.nanomuse.ui.sysfiles.ROUTE_SYSTEM_FILES) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nm_drawer_settings)) },
                        onClick = { menu = false; navController.safeNavigate(Routes.SETTINGS) },
                    )
                }
        }
    }
}
