package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import android.net.Uri
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun TasksScreen(
    app: AppState,
    detailLoads: Map<SessionKey, LoadState<Unit>>,
    modelLoads: Map<SessionKey, LoadState<ModelCatalog>>,
    operations: Set<OperationKey>,
    sessionOperations: Set<SessionOperationKey>,
    drafts: Map<SessionKey, SessionDraft>,
    fileSelections: Map<SessionKey, Int>,
    selectedSession: SessionKey?,
    onSessionSelected: (SessionKey) -> Unit,
    openResources: () -> Unit,
    connectHost: () -> Unit,
    loadSession: (Session, Boolean) -> Unit,
    loadModels: (Session, Boolean) -> Unit,
    onPrompt: (Session) -> Unit,
    onDraftTextChange: (SessionKey, String) -> Unit,
    onFileSelected: (SessionKey, Uri) -> Unit,
    onFileRemoved: (SessionKey, String) -> Unit,
    onCommand: (Session, SessionUserCommand) -> Unit,
    onRespond: (Session, JSONObject) -> Unit,
    onSelectModel: (Session, ModelInfo) -> Unit,
    onSetThinkingLevel: (Session, String) -> Unit,
    onLoadSavedHistory: (Session) -> Unit,
    onLoadEarlier: (Session) -> Unit,
) {
    // updatedAt changes continuously while an agent works; createdAt keeps the pager stable.
    val sessions = app.hosts.values
        .flatMap { it.sessions }
        .sortedWith { a, b -> compareTimestamps(b.createdAt, a.createdAt) }
    val initialPage = sessions.indexOfFirst { SessionKey(it.hostId, it.id) == selectedSession }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { sessions.size })
    val sessionKeys = sessions.map { SessionKey(it.hostId, it.id) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedSession, sessionKeys) {
        val target = sessionKeys.indexOf(selectedSession)
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState.currentPage, sessionKeys) {
        sessionKeys.getOrNull(pagerState.currentPage)?.let(onSessionSelected)
    }

    Column(Modifier.fillMaxSize()) {
        TasksTopBar(
            activeTaskCount = sessions.count { it.isActive },
            taskCount = sessions.size,
            showWorkspaces = app.hosts.isNotEmpty(),
            openResources = openResources,
        )
        TasksPagerBar(
            sessions = sessions,
            hosts = app.hosts,
            currentPage = pagerState.currentPage.coerceIn(0, sessions.lastIndex.coerceAtLeast(0)),
            selectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
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
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
                key = { sessionKeys[it] },
            ) { pageIndex ->
                val session = sessions[pageIndex]
                val key = SessionKey(session.hostId, session.id)
                val detail = app.details[key]
                LaunchedEffect(key, pagerState.currentPage, app.hosts[session.hostId]?.subscriptionId) {
                    if (pageIndex == pagerState.currentPage) loadSession(session, true)
                }
                val detailState = detail?.let { LoadState.Ready(it) }
                    ?: when (val request = detailLoads[key]) {
                        is LoadState.Failed -> request
                        else -> LoadState.Loading
                    }
                SessionPage(
                    state = detailState,
                    host = app.hosts[session.hostId],
                    draft = drafts[key] ?: SessionDraft(),
                    selectingFiles = fileSelections[key] ?: 0,
                    activity = sessionOperations.activity(key),
                    onRetry = { loadSession(session, true) },
                    onPrompt = { onPrompt(session) },
                    onDraftTextChange = { onDraftTextChange(key, it) },
                    onFileSelected = { onFileSelected(key, it) },
                    onFileRemoved = { onFileRemoved(key, it) },
                    onCommand = { command -> onCommand(session, command) },
                    onRespond = { body -> onRespond(session, body) },
                    modelState = modelLoads[key],
                    onLoadModels = { force -> loadModels(session, force) },
                    onSelectModel = { model -> onSelectModel(session, model) },
                    onSetThinkingLevel = { level -> onSetThinkingLevel(session, level) },
                    onLoadSavedHistory = { onLoadSavedHistory(session) },
                    onLoadEarlier = { onLoadEarlier(session) },
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Base0.copy(alpha = 0.90f))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Sessions",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .background(Violet400.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(Violet400, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                "$activeTaskCount active",
                style = MaterialTheme.typography.labelSmall,
                color = TextHigh,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "$taskCount total",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = TextMid,
        )
        if (showWorkspaces) {
            TextButton(
                onClick = rememberHapticOnClick(openResources),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    Modifier
                        .height(30.dp)
                        .background(Color.White.copy(alpha = 0.065f), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.brandGradientMask(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            Modifier.size(16.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Workspaces",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksPagerBar(
    sessions: List<Session>,
    hosts: Map<String, HostState>,
    currentPage: Int,
    selectPage: (Int) -> Unit,
) {
    val current = sessions.getOrNull(currentPage) ?: return

    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .zIndex(1f),
    ) {
        Row(
            Modifier
                .matchParentSize()
                .background(Base0.copy(alpha = 0.90f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskNeighborPreview(
                session = sessions.getOrNull(currentPage - 1),
                direction = -1,
                edgeLabel = "Newest",
                modifier = Modifier.weight(0.82f),
                onClick = { selectPage(currentPage - 1) },
            )
            Spacer(Modifier.width(6.dp))
            Column(
                Modifier
                    .weight(1.36f)
                    .fillMaxHeight()
                    .glassPanel(RoundedCornerShape(16.dp), fillAlpha = 0.13f, borderAlpha = 0.24f)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    current.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(statusColor(current.status), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${statusLabel(current.status)} · ${hosts[current.hostId]?.paired?.host?.name.orEmpty()}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${currentPage + 1}/${sessions.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Purple200,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            TaskNeighborPreview(
                session = sessions.getOrNull(currentPage + 1),
                direction = 1,
                edgeLabel = "Oldest",
                modifier = Modifier.weight(0.82f),
                onClick = { selectPage(currentPage + 1) },
            )
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

@Composable
private fun TaskNeighborPreview(
    session: Session?,
    direction: Int,
    edgeLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(Color.White.copy(alpha = if (session == null) 0.025f else 0.055f), shape)
            .clickable(enabled = session != null, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (direction < 0) {
            Icon(
                Icons.Outlined.ChevronLeft,
                contentDescription = null,
                Modifier.size(18.dp),
                tint = if (session == null) Gray400.copy(alpha = 0.35f) else Purple200,
            )
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = if (direction < 0) Alignment.Start else Alignment.End,
        ) {
            Text(
                session?.title ?: edgeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (session == null) Gray400.copy(alpha = 0.45f) else TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                session?.let { statusLabel(it.status) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = session?.let { statusColor(it.status) } ?: Color.Transparent,
                maxLines = 1,
            )
        }
        if (direction > 0) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                Modifier.size(18.dp),
                tint = if (session == null) Gray400.copy(alpha = 0.35f) else Purple200,
            )
        }
    }
}
