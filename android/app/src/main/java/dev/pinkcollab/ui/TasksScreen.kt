package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import kotlinx.coroutines.launch

internal data class TasksScreenState(
    val sessions: SessionListState,
    val details: Map<SessionKey, SessionDetail>,
    val detailLoads: Map<SessionKey, LoadState<Unit>>,
    val modelLoads: Map<SessionKey, LoadState<ModelCatalog>>,
    val sessionOperations: Set<SessionOperationKey>,
    val sendProgress: Map<SessionKey, SendProgress>,
    val drafts: Map<SessionKey, SessionDraft>,
    val fileSelections: Map<SessionKey, Int>,
    val selectedSession: SessionKey?,
    val usageLoads: Map<String, LoadState<UsageSnapshot>> = emptyMap(),
    val runtimeStarts: Map<SessionKey, RuntimeStartAttempt> = emptyMap(),
)

internal data class TasksScreenActions(
    val selectSession: (SessionKey) -> Unit,
    val openResources: () -> Unit,
    val connectHost: () -> Unit,
    val checkForUpdates: () -> Unit,
    val showVersion: () -> Unit,
    val session: (Session, SessionAction) -> Unit,
    val applyModelSettings: (Session, ModelSettingsChanges) -> Boolean,
    val retryHost: (String) -> Unit = {},
    val loadMoreSessions: (String) -> Unit = {},
    val ensureSessionListed: (SessionKey) -> Unit = {},
    val loadToolDetails: suspend (Session, String, String?) -> ToolDetailPage = { _, _, _ -> error("Tool details unavailable") },
)

@Composable
internal fun TasksScreen(
    state: TasksScreenState,
    actions: TasksScreenActions,
    retainedDisplay: androidx.compose.runtime.snapshots.SnapshotStateMap<SessionKey, SessionDisplay>? = null,
) {
    val hosts = state.sessions.hosts
    val detailLoads = state.detailLoads
    val modelLoads = state.modelLoads
    val sessionOperations = state.sessionOperations
    val sendProgress = state.sendProgress
    val drafts = state.drafts
    val fileSelections = state.fileSelections
    val selectedSession = state.selectedSession
    val onSessionSelected = actions.selectSession
    val openResources = actions.openResources
    val connectHost = actions.connectHost
    // Active sessions come first, by createdAt DESC to keep working sessions stable.
    // Inactive sessions follow by updatedAt DESC.
    val hostSessions = remember(hosts) { hosts.values.map { it.sessions } }
    val sessions = remember(hostSessions) {
        hostSessions.flatten().sortedWith(::compareSessionsForPresentation)
    }
    val initialPage = sessions.indexOfFirst { SessionKey(it.hostId, it.id) == selectedSession }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { sessions.size })
    val sessionKeys = remember(sessions) { sessions.map { SessionKey(it.hostId, it.id) } }
    val pagerKeys = remember(sessionKeys) { sessionKeys.map { it.pagerKey() } }
    val localDisplayCache = remember { mutableStateMapOf<SessionKey, SessionDisplay>() }
    val displayCache = retainedDisplay ?: localDisplayCache
    SideEffect {
        displayCache.keys.toList().filter { it !in sessionKeys }.forEach(displayCache::remove)
        state.details.forEach { (key, detail) ->
            if (key in sessionKeys) {
                sessionDetailForDisplay(detail, displayCache[key])?.let { displayCache[key] = it }
            }
        }
    }

    val scope = rememberCoroutineScope()

    // Do not publish the previous page while a navigation target is missing or being applied.
    var pendingSelection by remember(selectedSession) { mutableStateOf(selectedSession) }
    LaunchedEffect(selectedSession, sessionKeys) {
        val target = sessionKeys.indexOf(selectedSession)
        if (target < 0 && selectedSession != null && hosts[selectedSession.hostId]?.connected == true) actions.ensureSessionListed(selectedSession)
        if (target >= 0) {
            pendingSelection = selectedSession
            if (target != pagerState.currentPage) pagerState.scrollToPage(target)
            pendingSelection = null
        }
    }
    LaunchedEffect(pagerState, selectedSession, sessionKeys) {
        snapshotFlow {
            if (pendingSelection != null || pagerState.isScrollInProgress) null
            else sessionKeys.getOrNull(pagerState.settledPage)
        }.collect { key -> key?.let(onSessionSelected) }
    }

    Column(Modifier.fillMaxSize()) {
        TasksTopBar(
            activeTaskCount = sessions.count { it.isActive },
            taskCount = hosts.values.sumOf { it.totalSessions ?: it.sessions.size },
            showWorkspaces = hosts.isNotEmpty(),
            openResources = openResources,
            checkForUpdates = actions.checkForUpdates,
            showVersion = actions.showVersion,
        )
        TasksPagerBar(
            hosts = hosts,
            sessions = sessions,
            currentPage = pagerState.currentPage.coerceIn(0, sessions.lastIndex.coerceAtLeast(0)),
            selectPage = { page -> scope.launch { pagerState.scrollToPage(page) } },
            loadMore = { hosts.values.filter { it.connected && it.nextSessionsCursor != null }.forEach { actions.loadMoreSessions(it.paired.host.id) } },
            hasMore = hosts.values.any { it.nextSessionsCursor != null },
        )
        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f)) {
                when (state.sessions.emptyState) {
                    TaskListEmptyState.Loading -> TaskListLoadingState()
                    TaskListEmptyState.Recovery -> SessionRecoveryScreen(hosts.values.toList(), actions)

                    TaskListEmptyState.Ready -> if (hosts.isEmpty()) {
                        BringOmpEmptyState(connectHost = connectHost)
                    } else {
                        EmptyState(
                            title = "No sessions yet",
                            description = "Choose a workspace to create your first session.",
                            action = "Open workspaces",
                            onAction = openResources,
                        )
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).testTag("sessionTimelinePager"),
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
                key = { pagerKeys[it] },
            ) { pageIndex ->
                val session = sessions[pageIndex]
                val key = SessionKey(session.hostId, session.id)
                val detail = state.details[key]
                val displayed = sessionDetailForDisplay(detail, displayCache[key])
                val detailState = displayed?.let { LoadState.Ready(it.detail) }
                    ?: when (val request = detailLoads[key]) {
                        is LoadState.Failed -> request
                        else -> LoadState.Loading
                    }
                SessionPage(
                    isActive = pageIndex == pagerState.settledPage,
                    state = SessionPageState(
                        detail = detailState,
                        summary = session,
                        host = hosts[session.hostId],
                        draft = drafts[key] ?: SessionDraft(),
                        selectingFiles = fileSelections[key] ?: 0,
                        activity = sessionOperations.activity(key),
                        sendProgress = sendProgress[key],
                        model = modelLoads[key],
                        usage = state.usageLoads[session.hostId],
                        runtimeStart = state.runtimeStarts[key],
                        historyItems = displayed?.historyItems,
                        refreshError = if (detailLoads[key] is LoadState.Failed)
                            "Could not refresh this conversation" else null,
                    ),
                    onAction = { action -> actions.session(session, action) },
                    onApplyModelSettings = { changes -> actions.applyModelSettings(session, changes) },
                    loadToolDetails = { callId, cursor -> actions.loadToolDetails(session, callId, cursor) },
                )
            }
        }
    }
}

@Composable
private fun TaskListLoadingState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = BrandBrass)
        Spacer(Modifier.height(20.dp))
        Text(
            "Syncing sessions…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Waiting for the paired hosts to send their session lists.",
            color = TextMid,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private data class PairingStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
)

@Composable
private fun BringOmpEmptyState(modifier: Modifier = Modifier, connectHost: () -> Unit) {
    val steps = remember {
        listOf(
            PairingStep(
                Icons.Outlined.Terminal,
                "Create a pairing code",
                "Run the PinkCollab pairing command on your OMP host.",
            ),
            PairingStep(
                Icons.Outlined.QrCodeScanner,
                "Scan it with this phone",
                "The one-time code securely links this app to your host.",
            ),
            PairingStep(
                Icons.Outlined.EditNote,
                "Start your first session",
                "Pick a workspace, describe the outcome, and follow along.",
            ),
        )
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Bring OMP to your phone",
                    modifier = Modifier.brandGradientMask(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pair with the Gateway on your computer. Your code and tools stay on the host while you guide the work from here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .framedPanel(CardShape, fillAlpha = 0.075f, borderAlpha = 0.18f)
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    steps.forEachIndexed { index, step ->
                        PairingStepRow(index + 1, step)
                        if (index < steps.lastIndex) {
                            HorizontalDivider(
                                Modifier.padding(start = 50.dp),
                                color = Color.White.copy(alpha = 0.07f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    onClick = connectHost,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Connect OMP host")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can scan a QR code or enter info manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PairingStepRow(number: Int, step: PairingStep) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(BrandBrass.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(step.icon, contentDescription = null, Modifier.size(20.dp), tint = BrassLight)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$number. ${step.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                step.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
        }
    }
}

@Composable
private fun TasksTopBar(
    activeTaskCount: Int,
    taskCount: Int,
    showWorkspaces: Boolean,
    openResources: () -> Unit,
    checkForUpdates: () -> Unit,
    showVersion: () -> Unit,
) {
    var showConfMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (taskCount > 0) {
            Text(
                "Sessions $activeTaskCount/$taskCount active",
                Modifier.weight(1f).padding(end = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showWorkspaces) {
            TopBarAction(Icons.Outlined.FolderOpen, "Workspaces", openResources)
        }
        Box {
            TopBarAction(Icons.Outlined.Settings, "Conf") { showConfMenu = true }
            DropdownMenu(
                expanded = showConfMenu,
                onDismissRequest = { showConfMenu = false },
                modifier = Modifier.retroPanel(),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                shape = RetroShape,
            ) {
                DropdownMenuItem(
                    text = { Text("Check for updates") },
                    onClick = { showConfMenu = false; checkForUpdates() },
                )
                DropdownMenuItem(
                    text = { Text("Current version: v${dev.pinkcollab.BuildConfig.VERSION_NAME}") },
                    onClick = { showConfMenu = false; showVersion() },
                )
            }
        }
    }
}

@Composable
private fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = rememberHapticOnClick(onClick),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            Modifier
                .height(30.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = BrandBrass)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium, color = BrandBrass)
        }
    }
}

@Composable
private fun TasksPagerBar(
    hosts: Map<String, HostState>,
    sessions: List<Session>,
    currentPage: Int,
    selectPage: (Int) -> Unit,
    loadMore: () -> Unit,
    hasMore: Boolean,
) {
    if (sessions.isEmpty()) return
    val listState = rememberLazyListState()
    val sessionKeys = sessions.map { SessionKey(it.hostId, it.id) }
    LaunchedEffect(listState, sessions.size, hasMore) {
        if (hasMore) snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { if (it >= sessions.lastIndex - 3) loadMore() }
    }
    val cardWidth = 184.dp
    val startPadding = 12.dp
    val stripHeight = 40.dp + 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .zIndex(1f),
    ) {
        val centerOffset = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        val scrollOffsetPx = with(LocalDensity.current) { (startPadding - centerOffset).roundToPx() }
        LaunchedEffect(currentPage, sessionKeys, scrollOffsetPx) {
            // No leading spacer: the first card stays at the left edge.
            listState.animateScrollToItem(currentPage, scrollOffset = scrollOffsetPx)
        }
        LazyRow(
            state = listState,
            modifier = Modifier.matchParentSize().testTag("sessionCards"),
            contentPadding = PaddingValues(start = startPadding, end = centerOffset.coerceAtLeast(startPadding), top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(sessions, key = { _, session -> SessionKey(session.hostId, session.id).pagerKey() }) { index, session ->
                val cardStatus = sessionCardStatus(session, hosts[session.hostId]?.connection)
                val statusTop = when (cardStatus) {
                    SessionCardStatus.NeedsInput, SessionCardStatus.SignIn, SessionCardStatus.UpdateRequired -> RetroAmberTop
                    SessionCardStatus.Working, SessionCardStatus.Starting, SessionCardStatus.Stopping -> RetroSurfaceTop
                    SessionCardStatus.Ready -> RetroGreenTop
                    else -> RetroInsetTop
                }
                val statusBottom = when (cardStatus) {
                    SessionCardStatus.NeedsInput, SessionCardStatus.SignIn, SessionCardStatus.UpdateRequired -> RetroAmberBottom
                    SessionCardStatus.Working, SessionCardStatus.Starting, SessionCardStatus.Stopping -> RetroSurfaceBottom
                    SessionCardStatus.Ready -> RetroGreenBottom
                    else -> RetroInsetBottom
                }
                val selected = index == currentPage
                val fileName = session.cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { session.cwd }
                val titleColor = if (selected) BrassLight else RetroText
                val directoryColor = RetroMutedText
                Box(
                    Modifier
                        .width(cardWidth)
                        .testTag("sessionCard:${session.id}")
                        .fillMaxHeight()
                        .retroPanel(
                            if (selected) Color(0xFF494033) else RetroSurfaceTop,
                            if (selected) Color(0xFF342B23) else RetroSurfaceBottom,
                            accented = selected,
                        )
                        .semantics {
                            this.selected = selected
                            stateDescription = cardStatus.label
                        }
                        .clickable(onClick = rememberHapticOnClick { selectPage(index) }),
                ) {
                    // A full-height status stripe remains visible even when the badge is truncated.
                    Box(
                        Modifier
                            .padding(start = 3.dp, top = 8.dp, bottom = 8.dp)
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(Brush.verticalGradient(listOf(statusTop, statusBottom)), RetroShape),
                    )
                    Column(
                        Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            session.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = directoryColor,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                fileName,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                                color = directoryColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            cardStatus.label,
                            modifier = Modifier
                                .widthIn(max = cardWidth - 26.dp)
                                .retroPanel(statusTop, statusBottom, inset = true)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (statusTop == RetroAmberTop) RetroInk else RetroText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (hasMore) item(key = "loadEarlierSessions") {
                TextButton(onClick = loadMore) { Text("Load earlier") }
            }

        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 12.dp)
                .fillMaxWidth()
                .height(12.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.46f), Color.Transparent),
                    ),
                ),
        )
    }
}
