package dev.pinkcollab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    val hostOperations: Set<OperationKey> = emptySet(),
    val refreshBatch: HostRefreshBatch? = null,
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
    val createSession: (String, String) -> Unit = { _, _ -> },
    val refreshHosts: (List<String>) -> Unit = {},
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
    val onSessionSelected by rememberUpdatedState(actions.selectSession)
    val openResources = actions.openResources
    val connectHost = actions.connectHost
    // Active sessions come first, by createdAt DESC to keep working sessions stable.
    // Inactive sessions follow by updatedAt DESC. The host filter narrows that order; it does not reorder it.
    var requestedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val hostFilter = requestedHostId?.takeIf { it in hosts }
    val visibleState = remember(state.sessions, hostFilter) { state.sessions.forHostFilter(hostFilter) }
    val visibleHosts = visibleState.hosts
    val sessions = remember(visibleHosts) { sessionsForHostFilter(visibleHosts, null) }
    val initialPage = sessions.indexOfFirst { SessionKey(it.hostId, it.id) == selectedSession }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { sessions.size })
    val listedSessionKeys = remember(hosts) {
        hosts.values.flatMap { host -> host.sessions.map { SessionKey(it.hostId, it.id) } }.toSet()
    }
    val sessionKeys = remember(sessions) { sessions.map { SessionKey(it.hostId, it.id) } }
    var infoTarget by rememberSaveable(saver = SelectedSessionSaver) { mutableStateOf<SessionKey?>(null) }
    val infoSession = infoTarget?.takeIf { it == selectedSession }?.let { target ->
        hosts[target.hostId]?.sessions?.firstOrNull { SessionKey(it.hostId, it.id) == target }
    }
    SideEffect {
        if (infoTarget != null && infoSession == null) infoTarget = null
    }
    if (infoSession != null) {
        SessionInfoDialog(
            session = infoSession,
            host = hosts[infoSession.hostId],
            creating = OperationKey.CreateTask(infoSession.hostId, infoSession.cwd) in state.hostOperations,
            create = { actions.createSession(infoSession.hostId, infoSession.cwd) },
            dismiss = { infoTarget = null },
        )
    }
    val pagerKeys = remember(sessionKeys) { sessionKeys.map { it.pagerKey() } }
    val localDisplayCache = remember { mutableStateMapOf<SessionKey, SessionDisplay>() }
    val displayCache = retainedDisplay ?: localDisplayCache
    SideEffect {
        displayCache.keys.toList().filter { it !in listedSessionKeys }.forEach(displayCache::remove)
        state.details.forEach { (key, detail) ->
            if (key in sessionKeys) {
                sessionDetailForDisplay(detail, displayCache[key])?.let { displayCache[key] = it }
            }
        }
    }

    val scope = rememberCoroutineScope()

    // Apply new navigation before publishing pager changes; removed or intentionally hidden selections may fall back.
    val hiddenByFilter = hostFilter != null && selectedSession != null && selectedSession.hostId != hostFilter
    var appliedSelection by remember {
        mutableStateOf(selectedSession?.takeIf { hiddenByFilter || it in sessionKeys })
    }
    val selectedHostConnected = selectedSession?.let { hosts[it.hostId]?.connected } == true
    LaunchedEffect(pagerState, selectedSession, sessionKeys, hostFilter, selectedHostConnected) {
        val selectionChanged = appliedSelection != selectedSession
        if (selectionChanged && hiddenByFilter) {
            requestedHostId = null
            return@LaunchedEffect
        }
        val target = sessionKeys.indexOf(selectedSession)
        if (selectionChanged && selectedSession != null && !hiddenByFilter && target < 0) {
            if (selectedHostConnected) actions.ensureSessionListed(selectedSession)
            return@LaunchedEffect
        }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
        appliedSelection = selectedSession
        snapshotFlow {
            if (pagerState.isScrollInProgress) null else sessionKeys.getOrNull(pagerState.settledPage)
        }.collect { key -> key?.let(onSessionSelected) }
    }

    val cardStripHeight = 16.dp + 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxSize()) {
        TasksTopBar(
            hosts = hosts,
            selectedHostId = hostFilter,
            onSelectHost = { hostId ->
                requestedHostId = hostId
                sessionToSelectForHostFilter(selectedSession, sessionsForHostFilter(hosts, hostId?.takeIf { it in hosts }))
                    ?.let(onSessionSelected)
            },
            refreshHosts = actions.refreshHosts,
            batch = state.refreshBatch,
            showWorkspaces = hosts.isNotEmpty(),
            openResources = openResources,
            checkForUpdates = actions.checkForUpdates,
            showVersion = actions.showVersion,
        )
        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f)) {
                when (visibleState.emptyState) {
                    TaskListEmptyState.Loading -> TaskListLoadingState(
                        hostFilter?.let { id -> hostFilterOptions(hosts).firstOrNull { it.hostId == id }?.label },
                    )
                    TaskListEmptyState.Recovery -> SessionRecoveryScreen(visibleHosts.values.toList(), actions)

                    TaskListEmptyState.Ready -> if (hosts.isEmpty()) {
                        BringOmpEmptyState(connectHost = connectHost)
                    } else {
                        EmptyState(
                            title = if (hostFilter != null) "No sessions on this host" else "No sessions yet",
                            description = if (hostFilter != null) "Choose another host, or open a workspace to create one."
                                else "Choose a workspace to create your first session.",
                            action = "Open workspaces",
                            onAction = openResources,
                        )
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().testTag("sessionTimelinePager"),
                    beyondViewportPageCount = 1,
                    pageSpacing = 8.dp,
                    key = { pagerKeys[it] },
                ) { pageIndex ->
                    val session = sessions[pageIndex]
                    val key = SessionKey(session.hostId, session.id)
                    val detail = state.details[key]
                    val displayed = sessionDetailForDisplay(detail, displayCache[key])
                    val host = hosts[session.hostId]
                    if (sessionNeedsHostRecovery(displayed != null, host) && host != null) {
                        Box(Modifier.fillMaxSize().padding(top = cardStripHeight)) {
                            SessionRecoveryScreen(listOf(host), actions)
                        }
                    } else {
                        val detailState = displayed?.let { LoadState.Ready(it.detail) }
                            ?: if (host?.connected != true) LoadState.Loading
                            else when (val request = detailLoads[key]) {
                                is LoadState.Failed -> request
                                else -> LoadState.Loading
                            }
                        SessionPage(
                            isActive = pageIndex == pagerState.settledPage,
                            contentTopInset = cardStripHeight,
                            state = SessionPageState(
                                detail = detailState,
                                summary = session,
                                host = host,
                                draft = drafts[key] ?: SessionDraft(),
                                selectingFiles = fileSelections[key] ?: 0,
                                activity = sessionOperations.activity(key),
                                sendProgress = sendProgress[key],
                                model = modelLoads[key],
                                usage = state.usageLoads[session.hostId],
                                runtimeStart = state.runtimeStarts[key],
                                historyItems = displayed?.historyItems,
                                refreshError = if (host?.connected == true && detailLoads[key] is LoadState.Failed)
                                    "Could not refresh this conversation" else null,
                            ),
                            onAction = { action -> actions.session(session, action) },
                            onApplyModelSettings = { changes -> actions.applyModelSettings(session, changes) },
                            loadToolDetails = { callId, cursor -> actions.loadToolDetails(session, callId, cursor) },
                        )
                    }
                }
                TasksPagerBar(
                    hosts = visibleHosts,
                    sessions = sessions,
                    currentPage = pagerState.currentPage.coerceIn(0, sessions.lastIndex.coerceAtLeast(0)),
                    selectedSession = selectedSession,
                    showInfo = { infoTarget = it },
                    selectPage = { page -> scope.launch { pagerState.scrollToPage(page) } },
                    loadMore = {
                        visibleHosts.values.filter { it.connected && it.nextSessionsCursor != null }
                            .forEach { actions.loadMoreSessions(it.paired.host.id) }
                    },
                    hasMore = visibleHosts.values.any { it.nextSessionsCursor != null },
                    height = cardStripHeight,
                )
            }
        }
    }
}

@Composable
private fun TaskListLoadingState(hostName: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MorphIndicator(color = Purple400)
        Spacer(Modifier.height(20.dp))
        Text(
            "Syncing sessions…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!hostName.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                hostName,
                color = TextMid,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
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
                        .glassPanel(CardShape, fillAlpha = 0.075f, borderAlpha = 0.18f)
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
                Button(
                    onClick = rememberHapticOnClick(connectHost),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6846B2),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Connect OMP host")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can scan a QR code or enter info manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray400,
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
            Modifier.size(38.dp).background(Purple400.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(step.icon, contentDescription = null, Modifier.size(20.dp), tint = Purple200)
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
    hosts: Map<String, HostState>,
    selectedHostId: String?,
    onSelectHost: (String?) -> Unit,
    refreshHosts: (List<String>) -> Unit,
    batch: HostRefreshBatch?,
    showWorkspaces: Boolean,
    openResources: () -> Unit,
    checkForUpdates: () -> Unit,
    showVersion: () -> Unit,
) {
    var showConfMenu by remember { mutableStateOf(false) }
    var dismissedBatch by remember { mutableStateOf<Long?>(null) }
    val refreshBatch = batch?.takeUnless { it.id == dismissedBatch }
    val refreshFill = remember(batch?.id) { Animatable(0f) }
    val refreshOpacity = remember(batch?.id) { Animatable(0f) }
    LaunchedEffect(refreshBatch) {
        val current = refreshBatch ?: return@LaunchedEffect
        refreshOpacity.snapTo(1f)
        refreshFill.animateTo(current.progress, tween(360, easing = FastOutSlowInEasing))
        if (current.finished) {
            delay(180)
            refreshOpacity.animateTo(0f, tween(220))
            dismissedBatch = current.id
        }
    }
    val scopedHosts = if (selectedHostId == null) hosts.values else listOfNotNull(hosts[selectedHostId])
    val refreshing = scopedHosts.any {
        it.connection == ConnectionState.Connecting || it.connection == ConnectionState.Synchronizing
    }
    val refreshableHosts = scopedHosts.filter {
        it.connection is ConnectionState.Online || it.connection is ConnectionState.Offline
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(Base0.copy(alpha = 0.90f))
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hosts.isNotEmpty()) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                HostSessionFilter(
                    hosts, selectedHostId, onSelectHost, Modifier.weight(1f, fill = false),
                    refreshProgress = refreshBatch?.progress,
                    refreshFill = { refreshFill.value },
                    refreshOpacity = { refreshOpacity.value },
                )
                IconButton(
                    onClick = rememberHapticOnClick {
                        refreshHosts(refreshableHosts.map { it.paired.host.id })
                    },
                    enabled = refreshableHosts.isNotEmpty() && refreshBatch == null,
                    modifier = Modifier.testTag("refreshHosts").semantics {
                        contentDescription = if (selectedHostId == null) "Refresh all hosts" else "Refresh host"
                        if (refreshing) stateDescription = "Connecting or syncing"
                    },
                ) {
                    if (refreshing) {
                        OrbitDots(Modifier.size(18.dp), Purple400)
                    } else {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (refreshableHosts.isNotEmpty()) Purple400 else Gray400,
                        )
                    }
                }
            }
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
private fun HostSessionFilter(
    hosts: Map<String, HostState>,
    selectedHostId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    refreshProgress: Float? = null,
    refreshFill: () -> Float = { 0f },
    refreshOpacity: () -> Float = { 0f },
) {
    val options = remember(hosts) { hostFilterOptions(hosts) }
    val label = options.firstOrNull { it.hostId == selectedHostId }?.label ?: AllHostsFilterLabel
    var expanded by remember { mutableStateOf(false) }
    val refreshColor = MaterialTheme.colorScheme.tertiary
    BoxWithConstraints(modifier) {
        val filterMaxWidth = maxWidth
        Box(Modifier.widthIn(max = filterMaxWidth)) {
            TopBarTextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
                contentModifier = Modifier
                    .clip(ButtonDefaults.textShape)
                    .drawBehind {
                        drawRect(
                            color = refreshColor.copy(alpha = 0.8f * refreshOpacity()),
                            size = size.copy(width = size.width * refreshFill()),
                        )
                    },
                modifier = Modifier
                    .widthIn(max = filterMaxWidth)
                    .testTag("hostFilter")
                    .semantics {
                        contentDescription = "Filter sessions by host"
                        stateDescription = if (expanded) "$label, expanded" else label
                        if (refreshProgress != null) {
                            progressBarRangeInfo = ProgressBarRangeInfo(refreshProgress, 0f..1f)
                        }
                    },
            ) {
                Text(
                    label,
                    Modifier.widthIn(max = (filterMaxWidth - 33.dp).coerceAtLeast(0.dp)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Purple400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Purple400,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                HostFilterMenuItem(AllHostsFilterLabel, selected = selectedHostId == null, tag = "hostFilterOption:all") {
                    expanded = false
                    onSelect(null)
                }
                options.forEach { option ->
                    HostFilterMenuItem(option.label, selected = option.hostId == selectedHostId,
                        tag = "hostFilterOption:${option.hostId}", connection = hosts.getValue(option.hostId).connection) {
                        expanded = false
                        onSelect(option.hostId)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostFilterMenuItem(
    label: String,
    selected: Boolean,
    tag: String,
    connection: ConnectionState? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (connection != null) {
                    HostConnectionStatus(connection)
                }
            }
        },
        onClick = rememberHapticOnClick(onClick),
        leadingIcon = {
            Icon(
                if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) Purple400 else TextMid,
            )
        },
        modifier = Modifier.testTag(tag).semantics { this.selected = selected },
    )
}

@Composable
private fun HostConnectionStatus(connection: ConnectionState) {
    val (status, color) = when (connection) {
        ConnectionState.Connecting -> "Connecting" to BrandPink
        ConnectionState.Synchronizing -> "Syncing" to BrandPink
        is ConnectionState.Online -> "Connected" to Teal300
        is ConnectionState.Offline -> "Offline" to Gray400
        ConnectionState.AuthenticationRequired -> "Reconnect required" to Red400
        ConnectionState.UpgradeRequired -> "Update required" to Red400
    }
    Text(status, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun TopBarTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp),
    contentModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = rememberHapticOnClick(onClick),
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            Modifier.height(30.dp).then(contentModifier).padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TopBarTextButton(onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Purple400)
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Purple400,
        )
    }
}

@Composable
private fun TasksPagerBar(
    hosts: Map<String, HostState>,
    sessions: List<Session>,
    currentPage: Int,
    selectedSession: SessionKey?,
    showInfo: (SessionKey) -> Unit,
    selectPage: (Int) -> Unit,
    loadMore: () -> Unit,
    hasMore: Boolean,
    height: Dp,
) {
    if (sessions.isEmpty()) return
    val listState = rememberLazyListState()
    val sessionKeys = sessions.map { SessionKey(it.hostId, it.id) }
    val currentLoadMore by rememberUpdatedState(loadMore)
    LaunchedEffect(listState, sessionKeys, hasMore) {
        if (hasMore) snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { if (it >= sessions.lastIndex - 3) currentLoadMore() }
    }
    val cardWidth = 168.dp
    val startPadding = 12.dp
    val selectedCardFill = remember {
        Brush.linearGradient(listOf(lerp(Base1, BrandPurple, 0.46f), lerp(Base1, BrandPurple, 0.30f)))
    }

    val backgroundHeight = height + 8.dp
    val solidStop = (height / 2) / backgroundHeight
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height)
            .zIndex(1f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .height(backgroundHeight)
                .background(
                    Brush.verticalGradient(
                        0f to Base0,
                        solidStop to Base0,
                        1f to Color.Transparent,
                    ),
                ),
        )
        val centerOffset = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        val scrollOffsetPx = with(LocalDensity.current) { (startPadding - centerOffset).roundToPx() }
        LaunchedEffect(currentPage, sessionKeys, scrollOffsetPx) {
            // No leading spacer: the first card stays at the left edge.
            listState.animateScrollToItem(currentPage, scrollOffset = scrollOffsetPx)
        }
        LazyRow(
            state = listState,
            modifier = Modifier.matchParentSize().testTag("sessionCards"),
            contentPadding = PaddingValues(start = startPadding, end = centerOffset.coerceAtLeast(startPadding), top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(sessions, key = { _, session -> SessionKey(session.hostId, session.id).pagerKey() }) { index, session ->
                val cardStatus = sessionCardStatus(session, hosts[session.hostId]?.connection)
                val selected = SessionKey(session.hostId, session.id) ==
                    (selectedSession ?: sessionKeys.getOrNull(currentPage))
                val cardColor = when (cardStatus) {
                    SessionCardStatus.NeedsInput, SessionCardStatus.SignIn, SessionCardStatus.UpdateRequired -> Amber300
                    SessionCardStatus.Working, SessionCardStatus.Starting, SessionCardStatus.Stopping -> if (selected) Purple200 else Violet400
                    SessionCardStatus.Ready -> Teal300
                    else -> if (selected) TextMid else Gray400
                }
                val fileName = session.cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { session.cwd }
                val hostName = hosts[session.hostId]?.paired?.displayName?.trim()?.ifEmpty { null } ?: session.hostId
                val locationColor = if (selected) Purple200 else TextMid
                val shape = RoundedCornerShape(13.dp)
                Column(
                    Modifier
                        .width(cardWidth)
                        .testTag("sessionCard:${session.id}")
                        .fillMaxHeight()
                        .clip(shape)
                        .then(
                            if (selected) Modifier.background(selectedCardFill, shape)
                            else Modifier.background(lerp(Base0, Color.White, 0.055f), shape)
                        )
                        .semantics {
                            this.selected = selected
                            stateDescription = cardStatus.label
                        }
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (selected) "Show session info" else "Select session",
                            onClick = rememberHapticOnClick {
                                if (selected) showInfo(SessionKey(session.hostId, session.id)) else selectPage(index)
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            session.title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) TextHigh else TextMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            cardStatus.label,
                            Modifier.widthIn(max = 64.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
                            color = cardColor,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            hostName,
                            Modifier.widthIn(max = 72.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = locationColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(" › ", style = MaterialTheme.typography.labelSmall, color = locationColor)
                        Text(
                            fileName,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = locationColor,
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

    }
}

@Composable
private fun SessionInfoDialog(
    session: Session,
    host: HostState?,
    creating: Boolean,
    create: () -> Unit,
    dismiss: () -> Unit,
) {
    val created = remember(session.createdAt) { sessionInfoTime(session.createdAt) }
    val updated = remember(session.updatedAt) { sessionInfoTime(session.updatedAt) }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("sessionInfo"),
        title = { Text("Session info", style = DialogHeadlineStyle) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SessionInfoField("Title", session.title)
                    Column(
                        Modifier.testTag("sessionInfoHost"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Host", style = MaterialTheme.typography.labelSmall, color = TextMid)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                host?.paired?.displayName ?: session.hostId,
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextHigh,
                            )
                            host?.let { HostConnectionStatus(it.connection) }
                        }
                    }
                    SessionInfoField("Working directory", session.cwd)
                    SessionInfoField("Status", sessionCardStatus(session, host?.connection).label)
                    SessionInfoField("Created (local time)", created)
                    SessionInfoField("Updated (local time)", updated)
                    SessionInfoField("Session ID", session.id)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = rememberHapticOnClick(dismiss)) { Text("Close") }
        },
        confirmButton = {
            TextButton(
                onClick = rememberHapticOnClick(create),
                enabled = host?.connected == true && !creating,
                modifier = Modifier.testTag("newSessionFromInfo"),
            ) {
                if (creating) {
                    OrbitDots(Modifier.size(18.dp), LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (creating) "Creating…" else "New session here")
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    )
}

@Composable
private fun SessionInfoField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMid)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
    }
}

private fun sessionInfoTime(timestamp: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .format(Instant.parse(timestamp).atZone(ZoneId.systemDefault()))
}.getOrDefault(timestamp)
