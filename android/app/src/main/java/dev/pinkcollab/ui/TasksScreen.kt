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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun TasksScreen(
    app: AppState,
    detailLoads: Map<SessionKey, LoadState<Unit>>,
    operations: Set<OperationKey>,
    selectedSessionId: String,
    onSessionSelected: (String) -> Unit,
    openResources: () -> Unit,
    loadSession: (Session, Boolean) -> Unit,
    onPrompt: (Session, String, () -> Unit) -> Unit,
    onCommand: (Session, String) -> Unit,
    onRespond: (Session, JSONObject) -> Unit,
    onCycleModel: (Session) -> Unit,
) {
    // updatedAt changes continuously while an agent works; createdAt keeps the pager stable.
    val sessions = app.hosts.values
        .flatMap { it.sessions }
        .sortedWith { a, b -> compareTimestamps(b.createdAt, a.createdAt) }
    val initialPage = sessions.indexOfFirst { it.id == selectedSessionId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { sessions.size })
    val sessionIds = sessions.map { it.id }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedSessionId, sessionIds) {
        val target = sessions.indexOfFirst { it.id == selectedSessionId }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState.currentPage, sessionIds) {
        sessions.getOrNull(pagerState.currentPage)?.let { onSessionSelected(it.id) }
    }

    Column(Modifier.fillMaxSize()) {
        TasksTopBar(
            sessions = sessions,
            hosts = app.hosts,
            currentPage = pagerState.currentPage.coerceIn(0, sessions.lastIndex.coerceAtLeast(0)),
            openResources = openResources,
            selectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
        )
        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyState(
                    title = if (app.hosts.isEmpty()) "Bring OMP to your phone" else "No tasks yet",
                    description = if (app.hosts.isEmpty()) {
                        "Connect a host, then choose an allowed project directory."
                    } else {
                        "Choose a workspace and enter your first prompt."
                    },
                    action = "Open workspaces",
                    onAction = openResources,
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
                key = { sessionIds[it] },
            ) { pageIndex ->
                val session = sessions[pageIndex]
                val key = SessionKey(session.hostId, session.id)
                val detail = app.details[session.id]
                LaunchedEffect(key, detail == null) {
                    if (detail == null) loadSession(session, false)
                }
                val detailState = detail?.let { LoadState.Ready(it) }
                    ?: when (val request = detailLoads[key]) {
                        is LoadState.Failed -> request
                        else -> LoadState.Loading
                    }
                SessionPage(
                    state = detailState,
                    host = app.hosts[session.hostId],
                    busy = OperationKey.Session(key) in operations,
                    onRetry = { loadSession(session, true) },
                    onPrompt = { message, onSent -> onPrompt(session, message, onSent) },
                    onCommand = { command -> onCommand(session, command) },
                    onRespond = { body -> onRespond(session, body) },
                    onCycleModel = { onCycleModel(session) },
                )
            }
        }
    }
}

@Composable
private fun TasksTopBar(
    sessions: List<Session>,
    hosts: Map<String, HostState>,
    currentPage: Int,
    openResources: () -> Unit,
    selectPage: (Int) -> Unit,
) {
    val current = sessions.getOrNull(currentPage)
    Column(Modifier.fillMaxWidth().background(Base0.copy(alpha = 0.90f))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tasks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${sessions.size} total · ${hosts.values.count { it.connected }} hosts online",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMid,
                )
            }
            IconButton(
                onClick = openResources,
                modifier = Modifier.size(48.dp).background(BrandGradient, CircleShape),
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
            ) {
                Icon(Icons.Outlined.FolderOpen, "Open workspaces")
            }
        }
        if (current != null) {
            Row(
                Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp, vertical = 6.dp),
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
                        Text("${currentPage + 1}/${sessions.size}", style = MaterialTheme.typography.labelSmall, color = Purple200)
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
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
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
            .clickable(enabled = session != null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (direction < 0) {
            Icon(
                Icons.Outlined.ChevronLeft,
                null,
                Modifier.size(18.dp),
                tint = if (session == null) Gray400.copy(alpha = 0.35f) else Purple200,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = if (direction < 0) Alignment.Start else Alignment.End) {
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
                null,
                Modifier.size(18.dp),
                tint = if (session == null) Gray400.copy(alpha = 0.35f) else Purple200,
            )
        }
    }
}
