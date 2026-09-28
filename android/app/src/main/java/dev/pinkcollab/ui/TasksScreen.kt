package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import kotlinx.coroutines.launch

internal data class TasksScreenState(
    val app: AppState,
    val detailLoads: Map<SessionKey, LoadState<Unit>>,
    val modelLoads: Map<SessionKey, LoadState<ModelCatalog>>,
    val sessionOperations: Set<SessionOperationKey>,
    val sendProgress: Map<SessionKey, SendProgress>,
    val drafts: Map<SessionKey, SessionDraft>,
    val fileSelections: Map<SessionKey, Int>,
    val selectedSession: SessionKey?,
)

internal data class TasksScreenActions(
    val selectSession: (SessionKey) -> Unit,
    val openResources: () -> Unit,
    val connectHost: () -> Unit,
    val checkForUpdates: () -> Unit,
    val showVersion: () -> Unit,
    val session: (Session, SessionAction) -> Unit,
    val applyModelSettings: (Session, ModelSettingsChanges) -> Boolean,
)

@Composable
internal fun TasksScreen(state: TasksScreenState, actions: TasksScreenActions) {
    val app = state.app
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
    // updatedAt changes continuously while an agent works; createdAt keeps the pager stable.
    val sessions = app.hosts.values
        .flatMap { it.sessions }
        .sortedWith { a, b -> compareTimestamps(b.createdAt, a.createdAt) }
    val initialPage = sessions.indexOfFirst { SessionKey(it.hostId, it.id) == selectedSession }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { sessions.size })
    val sessionKeys = sessions.map { SessionKey(it.hostId, it.id) }
    val pagerKeys = remember(sessionKeys) { sessionKeys.map { it.pagerKey() } }

    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedSession, sessionKeys) {
        val target = sessionKeys.indexOf(selectedSession)
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState.settledPage, sessionKeys) {
        sessionKeys.getOrNull(pagerState.settledPage)?.let(onSessionSelected)
    }

    Column(Modifier.fillMaxSize()) {
        TasksTopBar(
            activeTaskCount = sessions.count { it.isActive },
            taskCount = sessions.size,
            showWorkspaces = app.hosts.isNotEmpty(),
            openResources = openResources,
            checkForUpdates = actions.checkForUpdates,
            showVersion = actions.showVersion,
        )
        TasksPagerBar(
            sessions = sessions,
            currentPage = pagerState.currentPage.coerceIn(0, sessions.lastIndex.coerceAtLeast(0)),
            selectPage = { page -> scope.launch { pagerState.scrollToPage(page) } },
        )
        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f)) {
                when (app.taskListLoadState) {
                    TaskListLoadState.Loading -> TaskListLoadingState()
                    TaskListLoadState.Unavailable -> EmptyState(
                        title = "Sessions unavailable",
                        description = "PinkCollab could not load sessions from the paired hosts.",
                        action = "Manage hosts",
                        onAction = openResources,
                        modifier = Modifier.offset(y = (-56).dp),
                    )

                    TaskListLoadState.Ready -> if (app.hosts.isEmpty()) {
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
                val detail = app.details[key]
                LaunchedEffect(key, pagerState.currentPage, app.hosts[session.hostId]?.subscriptionId) {
                    if (pageIndex == pagerState.currentPage) actions.session(session, SessionAction.Retry)
                }
                val detailState = detail?.let { LoadState.Ready(it) }
                    ?: when (val request = detailLoads[key]) {
                        is LoadState.Failed -> request
                        else -> LoadState.Loading
                    }
                SessionPage(
                    state = SessionPageState(
                        detail = detailState,
                        host = app.hosts[session.hostId],
                        draft = drafts[key] ?: SessionDraft(),
                        selectingFiles = fileSelections[key] ?: 0,
                        activity = sessionOperations.activity(key),
                        sendProgress = sendProgress[key],
                        model = modelLoads[key],
                    ),
                    onAction = { action -> actions.session(session, action) },
                    onApplyModelSettings = { changes -> actions.applyModelSettings(session, changes) },
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
        CircularProgressIndicator(color = Purple400)
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
            .background(Base0.copy(alpha = 0.90f))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Sessions $activeTaskCount/$taskCount active",
            Modifier.weight(1f).padding(end = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
        if (showWorkspaces) {
            TopBarPill(Icons.Outlined.FolderOpen, "Workspaces", openResources)
            Spacer(Modifier.width(6.dp))
        }
        Box {
            TopBarPill(Icons.Outlined.Settings, "Conf") { showConfMenu = true }
            DropdownMenu(expanded = showConfMenu, onDismissRequest = { showConfMenu = false }) {
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
private fun TopBarPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = rememberHapticOnClick(onClick),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            Modifier
                .height(30.dp)
                .background(Color.White.copy(alpha = 0.065f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Purple400)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium, color = Purple400)
        }
    }
}

@Composable
private fun TasksPagerBar(
    sessions: List<Session>,
    currentPage: Int,
    selectPage: (Int) -> Unit,
) {
    if (sessions.isEmpty()) return
    val listState = rememberLazyListState()
    val sessionKeys = sessions.map { SessionKey(it.hostId, it.id) }
    val cardWidth = 184.dp
    val startPadding = 12.dp

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
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
            modifier = Modifier.matchParentSize().background(Base0.copy(alpha = 0.90f)).testTag("sessionCards"),
            contentPadding = PaddingValues(start = startPadding, end = centerOffset.coerceAtLeast(startPadding), top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(sessions, key = { _, session -> SessionKey(session.hostId, session.id).pagerKey() }) { index, session ->
                val selected = index == currentPage
                val fileName = session.cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { session.cwd }
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .width(cardWidth)
                        .testTag("sessionCard:${session.id}")
                        .fillMaxHeight()
                        .clip(shape)
                        .then(
                            if (selected) Modifier.glassPanel(shape, fillAlpha = 0.13f, borderAlpha = 0.24f)
                            else Modifier.background(Color.White.copy(alpha = 0.055f), shape)
                        )
                        .clickable(onClick = rememberHapticOnClick { selectPage(index) })
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) TextHigh else TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fileName,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(6.dp).background(statusColor(session.status), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            statusLabel(session.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(session.status),
                            maxLines = 1,
                        )
                    }
                }
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
