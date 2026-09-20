package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
    connectHost: () -> Unit,
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
            if (app.hosts.isEmpty()) {
                BringOmpEmptyState(Modifier.weight(1f), connectHost)
            } else {
                Box(Modifier.weight(1f)) {
                    EmptyState(
                        title = "No tasks yet",
                        description = "Choose a workspace and enter your first prompt.",
                        action = "Open workspaces",
                        onAction = openResources,
                    )
                }
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
                "Start your first task",
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
                Row(
                    Modifier
                        .glassPanel(RoundedCornerShape(999.dp), fillAlpha = 0.08f, borderAlpha = 0.18f)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).background(Teal300, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "SET UP IN ABOUT A MINUTE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Purple200,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .size(92.dp)
                        .glassPanel(CircleShape, fillAlpha = 0.15f, borderAlpha = 0.32f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(62.dp).background(Purple400.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.AddLink,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = Purple200,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Bring OMP to your phone",
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
                PrimaryButton(
                    onClick = connectHost,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Connect your OMP host")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can scan a QR code or enter the connection details manually.",
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
