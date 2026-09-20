package dev.pinkcollab.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
                require(json.getInt("version") == 1) { "不支持此配对码版本" }
                pairURL = json.getString("url"); pairToken = json.getString("token"); page = "pair"
            }.onFailure { repo.error("无法识别 PinkCollab 配对码：${it.message}") }
        }
    }
    val secondary = page !in listOf("tasks", "workspaces", "hosts")
    BackHandler(secondary) { back() }
    PinkCollabTheme {
        Box(Modifier.fillMaxSize()) {
            GlowBackground(hazeState, Modifier.matchParentSize())
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    Box(Modifier.fillMaxWidth().hazeEffect(hazeState) { style = GlassBarStyle }) {
                        TopAppBar(
                            title = { Column { Text(when (page) { "tasks" -> "Tasks"; "workspaces" -> "Workspaces"; "hosts" -> "Hosts"; "session" -> "任务详情"; "browser" -> "选择工作目录"; "create" -> "新任务"; "pair" -> "连接 Host"; else -> "PinkCollab" }); if (!secondary) Text("PinkCollab · 远程管理 OMP", style = MaterialTheme.typography.labelSmall, color = Pink200) } },
                            navigationIcon = { if (secondary) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                            actions = { if (!secondary) IconButton(onClick = { pairToken = ""; page = "pair" }) { Icon(Icons.Outlined.AddLink, "连接 Host") } },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                        )
                    }
                },
                bottomBar = {
                    if (!secondary) Box(Modifier.fillMaxWidth().hazeEffect(hazeState) { style = GlassBarStyle }) {
                        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                            listOf(Triple("tasks", "Tasks", Icons.Outlined.TaskAlt), Triple("workspaces", "Workspaces", Icons.Outlined.FolderOpen), Triple("hosts", "Hosts", Icons.Outlined.Dns)).forEach { (key, title, icon) ->
                                NavigationBarItem(
                                    selected = page == key,
                                    onClick = { page = key; rootPage = key },
                                    icon = { Icon(icon, title) },
                                    label = { Text(title) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Pink200,
                                        selectedTextColor = Pink200,
                                        indicatorColor = Pink700.copy(alpha = 0.55f),
                                        unselectedIconColor = Gray400,
                                        unselectedTextColor = Gray400,
                                    ),
                                )
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (page == "tasks" && app.hosts.isNotEmpty()) GlowFab(onClick = { page = "workspaces"; rootPage = "workspaces" })
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
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
                        "pair" -> PairPage(pairURL, pairToken, app.loading, { pairURL = it }, { pairToken = it }, { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 Gateway 生成的配对码").setBeepEnabled(false).setOrientationLocked(false)) }, { vm.run { repo.pair(pairURL, pairToken); pairToken = ""; page = "hosts"; rootPage = "hosts" } })
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
            .padding(18.dp)
            .size(64.dp)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Pink400.copy(alpha = 0.35f), Color.Transparent), center = Offset(size.width / 2f, size.height / 2f)))
            }
            .size(56.dp)
            .background(PinkVioletBrush, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Add, "新任务", tint = Color(0xFF1A020C)) }
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
    if (app.hosts.isEmpty()) { EmptyState("把 OMP 带到手机上", "连接一台 Host，从允许的项目目录开始新任务。", "连接 Host", pair); return }
    val sessions = app.hosts.values.flatMap { it.sessions }.sortedWith { a, b -> compareTimestamps(b.updatedAt, a.updatedAt) }
    val attention = sessions.filter { it.needsAttention }
    val running = sessions.filter { !it.needsAttention && it.status in listOf("starting", "running") }
    val recent = sessions.filter { !it.needsAttention && it.status !in listOf("starting", "running") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${sessions.size} 个任务 · ${app.hosts.values.count { it.connected }} 台在线", style = MaterialTheme.typography.labelLarge, color = TextMid); TextButton(onClick = refresh, enabled = !app.loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text("刷新") } } }
        if (sessions.isEmpty()) item { Text("暂无任务。打开 Workspaces，选择目录并输入第一条 Prompt。", Modifier.padding(vertical = 40.dp), color = TextMid) }
        listOf("需要你处理" to attention, "进行中" to running, "最近任务" to recent).forEach { (title, group) ->
            if (group.isNotEmpty()) {
                item { Text(title, style = MaterialTheme.typography.titleMedium, color = if (title == "需要你处理") Pink400 else TextHigh) }
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
    if (app.hosts.isEmpty()) { EmptyState("还没有 Host", "每台运行 Gateway 的机器都可以独立配对。", "连接 Host", pair); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(app.hosts.values.toList(), key = { it.paired.host.id }) { h ->
            Card(Modifier.fillMaxWidth().glassPanel(CardShape), shape = CardShape, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(h.paired.host.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowDot(if (h.connected) Teal300 else Gray400)
                            Spacer(Modifier.width(6.dp))
                            Text(if (h.connected) "在线" else "离线", color = if (h.connected) Teal300 else Gray400, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text("${h.paired.host.os} · ${h.sessions.count { it.status in listOf("starting", "running", "needs_input") }} 个活跃任务", color = TextMid)
                    Text(h.paired.url, style = MaterialTheme.typography.bodySmall, color = Gray400)
                    Text("OMP ${h.paired.host.ompVersion} · Gateway ${h.paired.host.gatewayVersion}", style = MaterialTheme.typography.bodySmall, color = Gray400)
                    h.sessions.filter { it.status in listOf("running", "needs_input", "starting") }.forEach { s -> TextButton(onClick = { open(s) }, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text(s.title) } }
                    Row { TextButton(onClick = { refresh(h.paired.host.id) }, enabled = !app.loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text("刷新") }; TextButton(onClick = { forget(h.paired.host.id) }, enabled = !app.loading, colors = ButtonDefaults.textButtonColors(contentColor = Gray400)) { Text("移除本地配对") } }
                }
            }
        }
        item { OutlinedButton(onClick = pair, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Pink200)) { Icon(Icons.Outlined.AddLink, null); Spacer(Modifier.width(8.dp)); Text("连接另一台 Host") } }
    }
}

@Composable
private fun WorkspacePage(app: AppState, browse: (String, String) -> Unit, pair: () -> Unit) {
    if (app.hosts.isEmpty()) { EmptyState("从工作目录开始", "配对 Host 后，Gateway 允许的目录会显示在这里。", "连接 Host", pair); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        app.hosts.values.forEach { h ->
            item { Text(h.paired.host.name + if (h.connected) "" else " · 离线", style = MaterialTheme.typography.titleMedium) }
            if (h.workspaces.isEmpty()) item { Text("正在连接 Gateway 获取目录…", style = MaterialTheme.typography.bodySmall, color = TextMid) }
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
    if (listing == null) { EmptyState(if (loading) "正在读取目录" else "目录未能加载", "可以返回 Workspaces 重新选择。"); return }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp).glassPanel(CardShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hostName, style = MaterialTheme.typography.labelLarge, color = Pink400)
            Text(listing.path, style = MaterialTheme.typography.titleMedium)
            listing.branch?.let { Text("Git · $it", style = MaterialTheme.typography.labelLarge, color = Violet400); Text(listing.gitStatus?.ifEmpty { "工作区干净" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = TextMid) }
            GradientButton(onClick = { select(listing.path) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("在此目录创建任务") }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            listing.parent?.let { parent -> item { TextButton(onClick = { browse(parent) }, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Icon(Icons.Outlined.ArrowUpward, null); Spacer(Modifier.width(8.dp)); Text("上级目录") } } }
            items(listing.directories, key = { it.path }) { w -> TextButton(onClick = { browse(w.path) }, enabled = !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = TextHigh)) { Icon(Icons.Outlined.Folder, null, tint = Pink400); Spacer(Modifier.width(12.dp)); Text(w.name, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = Gray400) } }
            if (listing.directories.isEmpty()) item { Text("没有可浏览的子目录", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = TextMid) }
        }
    }
}

@Composable
private fun CreatePage(host: HostState?, cwd: String, loading: Boolean, changeDirectory: () -> Unit, start: (String) -> Unit) {
    var prompt by rememberSaveable(cwd) { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Host", style = MaterialTheme.typography.labelMedium, color = TextMid); Text(host?.paired?.host?.name.orEmpty(), style = MaterialTheme.typography.titleMedium) }
        item { Text("工作目录", style = MaterialTheme.typography.labelMedium, color = TextMid); Text(cwd); TextButton(onClick = changeDirectory, enabled = !loading, colors = ButtonDefaults.textButtonColors(contentColor = Pink200), contentPadding = PaddingValues(0.dp)) { Text("更换目录") } }
        item { OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("希望 OMP 完成什么？") }, placeholder = { Text("修复 checkout 的竞态问题，并运行测试") }, minLines = 5, modifier = Modifier.fillMaxWidth(), enabled = !loading) }
        item { GradientButton(onClick = { start(prompt) }, enabled = prompt.isNotBlank() && !loading && host?.connected == true, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (loading) "正在启动 OMP…" else "开始任务") } }
    }
}

@Composable
private fun PairPage(url: String, token: String, loading: Boolean, onURL: (String) -> Unit, onToken: (String) -> Unit, scan: () -> Unit, pair: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("连接你的 OMP Host", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("在 Host 上运行 Gateway 的 pair 命令，扫描生成的二维码。配对码 5 分钟内有效。", color = TextMid) }
        item { GradientButton(onClick = scan, modifier = Modifier.fillMaxWidth(), enabled = !loading) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("扫描配对码") } }
        item { Text("也可以手动填写", style = MaterialTheme.typography.labelLarge, color = TextMid) }
        item { OutlinedTextField(url, onURL, label = { Text("Gateway 地址") }, placeholder = { Text("https://dev-server.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading) }
        item { OutlinedTextField(token, onToken, label = { Text("一次性配对令牌") }, modifier = Modifier.fillMaxWidth(), enabled = !loading) }
        item { OutlinedButton(onClick = pair, enabled = url.isNotBlank() && token.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Pink200)) { Text("完成配对") } }
    }
}
