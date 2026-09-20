package dev.pinkcollab.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import org.json.JSONObject

private val CardShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(999.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinkCollabApp(vm: CollabViewModel = viewModel()) {
    val repo = vm.repository
    val app by repo.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("tasks") }
    var rootPage by rememberSaveable { mutableStateOf("tasks") }
    var hostId by rememberSaveable { mutableStateOf("") }
    var sessionId by rememberSaveable { mutableStateOf("") }
    var cwd by rememberSaveable { mutableStateOf("") }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var pairURL by rememberSaveable { mutableStateOf("") }
    var pairToken by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val hazeState = remember { HazeState() }
    LaunchedEffect(app.error) { app.error?.let { snackbar.showSnackbar(it); repo.error(null) } }
    fun back() { page = rootPage }
    fun openSession(s: Session) {
        hostId = s.hostId; sessionId = s.id; page = "session"
        vm.run { repo.detail(s.hostId, s.id) }
    }
    fun browse(id: String, path: String) {
        hostId = id; listing = null; page = "browser"
        vm.run { listing = repo.listing(id, path) }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value ->
            runCatching {
                val json = JSONObject(value)
                require(json.getInt("version") == 1) { "Unsupported pairing code version" }
                pairURL = json.getString("url"); pairToken = json.getString("token"); page = "pair"
            }.onFailure { repo.error("Unrecognized PinkCollab pairing code: ${it.message}") }
        }
    }
    val secondary = page !in listOf("tasks", "workspaces", "hosts")
    BackHandler(secondary) { back() }
    PinkCollabTheme {
        Box(Modifier.fillMaxSize()) {
            GlowBackground(hazeState, Modifier.matchParentSize())
            Scaffold(
                containerColor = Color.Transparent,
                // Insets are handled explicitly: the top bar owns the status bar, the floating
                // pill owns the navigation bar, and pages without the pill pad themselves below.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Box(Modifier.fillMaxWidth().hazeEffect(hazeState) { style = GlassBarStyle }) {
                        TopAppBar(
                            title = { Column { Text(when (page) { "tasks" -> "Tasks"; "workspaces" -> "Workspaces"; "hosts" -> "Hosts"; "session" -> "Task"; "browser" -> "Choose directory"; "create" -> "New task"; "pair" -> "Connect host"; else -> "PinkCollab" }); if (!secondary) Text("PinkCollab · remote OMP control", style = MaterialTheme.typography.labelSmall, color = Pink200) } },
                            navigationIcon = { if (secondary) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                            actions = { if (!secondary) IconButton(onClick = { pairToken = ""; page = "pair" }) { Icon(Icons.Outlined.AddLink, "Connect host") } },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                        )
                    }
                },
                bottomBar = {
                    if (!secondary) FloatingPillNav(
                        hazeState = hazeState,
                        current = page,
                        onSelect = { key -> page = key; rootPage = key },
                    )
                },
                floatingActionButton = {
                    if (page == "tasks" && app.hosts.isNotEmpty()) GlowFab(onClick = { page = "workspaces"; rootPage = "workspaces" })
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .then(if (secondary) Modifier.navigationBarsPadding() else Modifier),
                ) {
                    if (app.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Pink400, trackColor = Pink700.copy(alpha = 0.30f))
                    when (page) {
                        "tasks" -> TasksPage(app, ::openSession, { page = "pair" }, { vm.run { app.hosts.keys.forEach { repo.refreshHost(it) } } })
                        "hosts" -> HostsPage(app, { page = "pair" }, { id -> vm.run { repo.refreshHost(id) } }, { id -> vm.run { repo.forget(id) } }, ::openSession)
                        "workspaces" -> WorkspacePage(app, ::browse, { page = "pair" })
                        "browser" -> BrowserPage(listing, app.hosts[hostId]?.paired?.host?.name.orEmpty(), app.loading, { path -> browse(hostId, path) }, { path -> cwd = path; page = "create" })
                        "create" -> CreatePage(app.hosts[hostId], cwd, app.loading, { page = "browser" }, { prompt -> vm.run { val s = repo.create(hostId, cwd, prompt); sessionId = s.id; page = "session"; repo.detail(hostId, s.id) } })
                        "session" -> {
                            val detail = app.details[sessionId]
                            SessionPage(detail, app.hosts[hostId], app.loading,
                                onPrompt = { message, onSent -> vm.run { repo.command(hostId, sessionId, "prompt", JSONObject().put("message", message)); onSent() } },
                                onCommand = { command -> vm.run { repo.command(hostId, sessionId, command) } },
                                onRespond = { body -> vm.run { repo.command(hostId, sessionId, "respond", body) } },
                                onCycleModel = { vm.run { repo.cycleModel(hostId, sessionId) } })
                        }
                        "pair" -> PairPage(pairURL, pairToken, app.loading, { pairURL = it }, { pairToken = it }, { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the pairing code from the Gateway").setBeepEnabled(false).setOrientationLocked(false)) }, { vm.run { repo.pair(pairURL, pairToken); pairToken = ""; page = "hosts"; rootPage = "hosts" } })
                    }
                }
            }
        }
    }
}

/** FAB with a pink halo: gradient disc over a soft radial glow. */
@Composable
private fun GlowFab(onClick: () -> Unit) {
    Box(
        Modifier
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 104.dp)
            .size(74.dp)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Pink400.copy(alpha = 0.38f), Color.Transparent), center = Offset(size.width / 2f, size.height / 2f)))
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(PinkVioletBrush, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "New task", tint = Color(0xFF1A020C)) }
    }
}

/** Floating capsule navigation: selected entry expands to show its label. */
@Composable
private fun FloatingPillNav(hazeState: HazeState, current: String, onSelect: (String) -> Unit) {
    val items = listOf(
        Triple("tasks", "Tasks", Icons.Outlined.TaskAlt),
        Triple("workspaces", "Workspaces", Icons.Outlined.FolderOpen),
        Triple("hosts", "Hosts", Icons.Outlined.Dns),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.radialGradient(listOf(Pink400.copy(alpha = 0.30f), Color.Transparent)),
                        topLeft = Offset(-40f, -14f),
                        size = Size(size.width + 80f, size.height + 28f),
                        cornerRadius = CornerRadius(40f, 40f),
                    )
                }
                .clip(PillShape)
                .hazeEffect(hazeState) { style = GlassBarStyle }
                .glassPanel(PillShape, fillAlpha = 0.11f, borderAlpha = 0.22f)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { (key, title, icon) ->
                val selected = current == key
                Row(
                    Modifier
                        .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        .clip(PillShape)
                        .then(if (selected) Modifier.background(PinkVioletBrush, PillShape) else Modifier)
                        .clickable { onSelect(key) }
                        .padding(horizontal = if (selected) 16.dp else 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, title, Modifier.size(22.dp), tint = if (selected) Color(0xFF1A020C) else Gray400)
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Text(title, color = Color(0xFF1A020C), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .size(96.dp)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Pink400.copy(alpha = 0.30f), Color.Transparent)))
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Hub, null, Modifier.size(52.dp), tint = Pink400) }
        Spacer(Modifier.height(24.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        action?.let { Spacer(Modifier.height(24.dp)); GradientButton(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun TasksPage(app: AppState, open: (Session) -> Unit, pair: () -> Unit, refresh: () -> Unit) {
    if (app.hosts.isEmpty()) { EmptyState("Bring OMP to your phone", "Connect a host, then start a task from an allowed project directory.", "Connect host", pair); return }
    val sessions = app.hosts.values.flatMap { it.sessions }.sortedWith { a, b -> compareTimestamps(b.updatedAt, a.updatedAt) }
    val attention = sessions.filter { it.needsAttention }
    val running = sessions.filter { !it.needsAttention && it.status in listOf("starting", "running") }
    val recent = sessions.filter { !it.needsAttention && it.status !in listOf("starting", "running") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${sessions.size} tasks · ${app.hosts.values.count { it.connected }} online", style = MaterialTheme.typography.labelLarge, color = TextMid); TextButton(onClick = refresh, enabled = !app.loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text("Refresh") } } }
        if (sessions.isEmpty()) item { Text("No tasks yet. Open Workspaces, pick a directory, and enter your first prompt.", Modifier.padding(vertical = 40.dp), color = TextMid) }
        listOf("Needs attention" to attention, "Running" to running, "Recent" to recent).forEach { (title, group) ->
            if (group.isNotEmpty()) {
                item { Text(title, style = MaterialTheme.typography.titleMedium, color = if (title == "Needs attention") Pink400 else TextHigh) }
                items(group, key = { it.id }) { s -> SessionCard(s, app.hosts[s.hostId], { open(s) }) }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun SessionCard(s: Session, host: HostState?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        Modifier
            .fillMaxWidth()
            .then(if (s.needsAttention) Modifier.pulsingGlowBorder(CardShape) else Modifier)
            .glassPanel(CardShape, fillAlpha = if (s.needsAttention) 0.11f else 0.07f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(host?.paired?.host?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = TextMid)
                if (host?.connected == false) StatusChip("offline") else StatusChip(s.status)
            }
            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(s.attention?.text ?: s.activity, style = MaterialTheme.typography.bodyMedium, color = TextMid, maxLines = 2)
            Text(s.cwd, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = Gray400)
        }
    }
}

@Composable
private fun HostsPage(app: AppState, pair: () -> Unit, refresh: (String) -> Unit, forget: (String) -> Unit, open: (Session) -> Unit) {
    if (app.hosts.isEmpty()) { EmptyState("No hosts yet", "Every machine running the Gateway pairs on its own.", "Connect host", pair); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(app.hosts.values.toList(), key = { it.paired.host.id }) { h ->
            Card(Modifier.fillMaxWidth().glassPanel(CardShape), shape = CardShape, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(h.paired.host.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowDot(if (h.connected) Teal300 else Gray400)
                            Spacer(Modifier.width(6.dp))
                            Text(if (h.connected) "Online" else "Offline", color = if (h.connected) Teal300 else Gray400, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text("${h.paired.host.os} · ${h.sessions.count { it.status in listOf("starting", "running", "needs_input") }} active tasks", color = TextMid)
                    Text(h.paired.url, style = MaterialTheme.typography.bodySmall, color = Gray400)
                    Text("OMP ${h.paired.host.ompVersion} · Gateway ${h.paired.host.gatewayVersion}", style = MaterialTheme.typography.bodySmall, color = Gray400)
                    h.sessions.filter { it.status in listOf("running", "needs_input", "starting") }.forEach { s -> TextButton(onClick = { open(s) }, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text(s.title) } }
                    Row { TextButton(onClick = { refresh(h.paired.host.id) }, enabled = !app.loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text("Refresh") }; TextButton(onClick = { forget(h.paired.host.id) }, enabled = !app.loading, colors = ButtonDefaults.textButtonColors(contentColor = Gray400)) { Text("Remove local pairing") } }
                }
            }
        }
        item { OutlinedButton(onClick = pair, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Pink200)) { Icon(Icons.Outlined.AddLink, null); Spacer(Modifier.width(8.dp)); Text("Connect another host") } }
    }
}

@Composable
private fun WorkspacePage(app: AppState, browse: (String, String) -> Unit, pair: () -> Unit) {
    if (app.hosts.isEmpty()) { EmptyState("Start from a directory", "After pairing a host, the directories the Gateway allows appear here.", "Connect host", pair); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        app.hosts.values.forEach { h ->
            item { Text(h.paired.host.name + if (h.connected) "" else " · offline", style = MaterialTheme.typography.titleMedium) }
            if (h.workspaces.isEmpty()) item { Text("Connecting to the Gateway…", style = MaterialTheme.typography.bodySmall, color = TextMid) }
            items(h.workspaces, key = { h.paired.host.id + it.path }) { w ->
                Card(
                    onClick = { browse(h.paired.host.id, w.path) },
                    enabled = h.connected,
                    modifier = Modifier.fillMaxWidth().glassPanel(CardShape),
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = Pink400); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) { Text(w.name, style = MaterialTheme.typography.titleMedium); Text(w.path, style = MaterialTheme.typography.bodySmall, color = TextMid) }; Icon(Icons.Outlined.ChevronRight, null, tint = Gray400)
                } }
            }
        }
    }
}

@Composable
private fun BrowserPage(listing: Listing?, hostName: String, loading: Boolean, browse: (String) -> Unit, select: (String) -> Unit) {
    if (listing == null) { EmptyState(if (loading) "Reading directory" else "Directory unavailable", "Go back to Workspaces and choose again."); return }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp).glassPanel(CardShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hostName, style = MaterialTheme.typography.labelLarge, color = Pink400)
            Text(listing.path, style = MaterialTheme.typography.titleMedium)
            listing.branch?.let { Text("Git · $it", style = MaterialTheme.typography.labelLarge, color = Violet400); Text(listing.gitStatus?.ifEmpty { "Working tree clean" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = TextMid) }
            GradientButton(onClick = { select(listing.path) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Create task here") }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            listing.parent?.let { parent -> item { TextButton(onClick = { browse(parent) }, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Icon(Icons.Outlined.ArrowUpward, null); Spacer(Modifier.width(8.dp)); Text("Parent directory") } } }
            items(listing.directories, key = { it.path }) { w -> TextButton(onClick = { browse(w.path) }, enabled = !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = TextHigh)) { Icon(Icons.Outlined.Folder, null, tint = Pink400); Spacer(Modifier.width(12.dp)); Text(w.name, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = Gray400) } }
            if (listing.directories.isEmpty()) item { Text("No subdirectories to browse", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = TextMid) }
        }
    }
}

@Composable
private fun CreatePage(host: HostState?, cwd: String, loading: Boolean, changeDirectory: () -> Unit, start: (String) -> Unit) {
    var prompt by rememberSaveable(cwd) { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Host", style = MaterialTheme.typography.labelMedium, color = TextMid); Text(host?.paired?.host?.name.orEmpty(), style = MaterialTheme.typography.titleMedium) }
        item { Text("Working directory", style = MaterialTheme.typography.labelMedium, color = TextMid); Text(cwd); TextButton(onClick = changeDirectory, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200), contentPadding = PaddingValues(0.dp)) { Text("Change") } }
        item { OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("What should OMP do?") }, placeholder = { Text("Fix the checkout race and run the tests") }, minLines = 5, modifier = Modifier.fillMaxWidth(), enabled = !loading) }
        item { GradientButton(onClick = { start(prompt) }, enabled = prompt.isNotBlank() && !loading && host?.connected == true, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (loading) "Starting OMP…" else "Start task") } }
    }
}

@Composable
private fun PairPage(url: String, token: String, loading: Boolean, onURL: (String) -> Unit, onToken: (String) -> Unit, scan: () -> Unit, pair: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Connect your OMP host", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("Run the pair command on the host and scan the QR code it prints. The code expires in 5 minutes.", color = TextMid) }
        item { GradientButton(onClick = scan, modifier = Modifier.fillMaxWidth(), enabled = !loading) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan pairing code") } }
        item { Text("Or enter it manually", style = MaterialTheme.typography.labelLarge, color = TextMid) }
        item { OutlinedTextField(url, onURL, label = { Text("Gateway address") }, placeholder = { Text("https://dev-server.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading) }
        item { OutlinedTextField(token, onToken, label = { Text("One-time pairing token") }, modifier = Modifier.fillMaxWidth(), enabled = !loading) }
        item { OutlinedButton(onClick = pair, enabled = url.isNotBlank() && token.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Pink200)) { Text("Pair") } }
    }
}
