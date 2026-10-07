package dev.pinkcollab.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pinkcollab.BuildConfig
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.PinkCollabTheme
import dev.pinkcollab.ui.theme.rememberHapticOnClick

@Composable
fun PinkCollabApp(vm: CollabViewModel = viewModel()) {
    val app by vm.navigationState.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()
    val availableUpdate by vm.availableUpdate.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = AppRouteSaver) { mutableStateOf<AppRoute>(AppRoute.Tasks) }
    var pairHost by rememberSaveable(stateSaver = PairHostSheetStateSaver) {
        mutableStateOf(PairHostSheetState())
    }
    var pairAttemptSequence by rememberSaveable { mutableLongStateOf(0L) }
    var selectedSession by rememberSaveable(saver = SelectedSessionSaver) { mutableStateOf<SessionKey?>(null) }
    var startupReady by remember { mutableStateOf(false) }
    val sessionDisplayCache = remember { mutableStateMapOf<SessionKey, SessionDisplay>() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        var hasStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (hasStarted) vm.reconnectHosts()
                hasStarted = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm) {
        awaitStartupReadiness(vm.appState)
        startupReady = true
    }
    LaunchedEffect(vm, route, lifecycleOwner) {
        if (route == AppRoute.Tasks) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.followTaskFocus(snapshotFlow { selectedSession })
            }
        }
    }
    LaunchedEffect(startupReady, vm) {
        if (startupReady) vm.checkForUpdates(manual = false)
    }

    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is UiEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                is UiEffect.HostPaired -> if (pairHost.attemptId == effect.attemptId) {
                    pairHost = PairHostSheetState()
                    route = AppRoute.Resources
                }
                is UiEffect.PairingFailed -> if (pairHost.attemptId == effect.attemptId) {
                    pairHost = pairHost.copy(error = effect.message, attemptId = 0)
                }
                is UiEffect.SessionCreated -> {
                    selectedSession = effect.key
                    route = AppRoute.Tasks
                }
            }
        }
    }

    var knownDirectories by remember { mutableStateOf(emptyMap<String, List<String>>()) }
    fun noteDirectory(hostId: String, path: String) {
        val windows = hostUsesWindowsPaths(app.hosts[hostId]?.host?.os.orEmpty())
        knownDirectories = knownDirectories + (hostId to noteServerPath(knownDirectories[hostId].orEmpty(), path, windows))
    }

    val secondary = route != AppRoute.Tasks
    val browserBackTarget = (route as? AppRoute.Browser)?.let { current ->
        val host = app.hosts[current.hostId]
        val ready = directory?.takeIf { it.key == BrowserKey(current.hostId, current.path) }?.state as? LoadState.Ready
        browserParentTarget(
            path = current.path,
            listingReady = ready != null,
            listingParent = ready?.value?.parent,
            roots = host?.workspaces?.map { it.path }.orEmpty(),
            windows = hostUsesWindowsPaths(host?.host?.os.orEmpty()),
        )
    }
    val goBack = {
        val current = route
        val parent = browserBackTarget
        if (current is AppRoute.Browser && parent != null) {
            val listingReady = directory?.takeIf { it.key == BrowserKey(current.hostId, current.path) }?.state is LoadState.Ready
            if (listingReady) noteDirectory(current.hostId, parent)
            route = current.copy(path = parent)
        } else route = current.back()
    }
    val secondaryTitle = when (val current = route) {
        AppRoute.Tasks -> null
        AppRoute.Resources -> "Workspaces"
        is AppRoute.Browser -> browserTitle(app.hosts[current.hostId]?.displayName.orEmpty())
    }
    BackHandler(secondary) { goBack() }

    PinkCollabTheme {
        Crossfade(
            targetState = startupReady,
            animationSpec = tween(150),
            label = "startupContent",
        ) { ready ->
            if (!ready) {
                StartupLoadingScreen(app)
            } else Box(Modifier.fillMaxSize().background(Base0)) {
            AppScaffold(
                title = secondaryTitle,
                hasParentDirectory = browserBackTarget != null,
                onBack = goBack,
            ) {
                    when (val current = route) {
                        AppRoute.Tasks -> TasksRoute(
                            retainedDisplay = sessionDisplayCache,
                            vm = vm,
                            selectedSession = selectedSession,
                            actions = TasksScreenActions(
                                selectSession = { selectedSession = it },
                                openResources = { route = AppRoute.Resources },
                                connectHost = { pairHost = PairHostSheetState(visible = true) },
                                checkForUpdates = { vm.checkForUpdates(manual = true) },
                                showVersion = {
                                    Toast.makeText(context, "Current version: v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                                },
                                session = vm::onSessionAction,
                                loadToolDetails = vm::loadToolDetails,
                                applyModelSettings = vm::applyModelSettings,
                                retryHost = vm::refreshHost,
                                loadMoreSessions = vm::loadMoreSessions,
                                ensureSessionListed = vm::ensureSessionListed,
                            ),
                        )

                        AppRoute.Resources -> ResourcesScreen(
                            app = app,
                            hostBusy = { OperationKey.Host(it) in operations },
                            browse = { hostId, path ->
                                noteDirectory(hostId, path)
                                route = AppRoute.Browser(hostId, path)
                            },
                            pair = { pairHost = PairHostSheetState(visible = true) },
                            refresh = vm::refreshHost,
                            rename = vm::renameHost,
                            forget = vm::forgetHost,
                        )

                        is AppRoute.Browser -> {
                            val host = app.hosts[current.hostId]
                            BrowserRoute(
                                route = current,
                                hostOs = host?.host?.os.orEmpty(),
                                roots = host?.workspaces?.map { it.path }.orEmpty(),
                                parentPath = browserBackTarget,
                                knownPaths = knownDirectories[current.hostId].orEmpty(),
                                creating = OperationKey.CreateTask(current.hostId, current.path) in operations,
                                state = directory?.takeIf { it.key == BrowserKey(current.hostId, current.path) }?.state,
                                load = { forceRefresh -> vm.loadDirectory(BrowserKey(current.hostId, current.path), forceRefresh) },
                                browse = { path ->
                                    noteDirectory(current.hostId, path)
                                    route = current.copy(path = path)
                                },
                                select = { path -> vm.createSession(current.hostId, path) },
                                note = { noteDirectory(current.hostId, it) },
                            )
                        }

                    }
                }

            PairHostModal(
                state = pairHost,
                onStateChange = { pairHost = it },
                onPair = { url, token ->
                    pairAttemptSequence += 1
                    val attemptId = pairAttemptSequence
                    pairHost = pairHost.copy(error = null, attemptId = attemptId)
                    vm.pairHost(url, token, attemptId)
                },
            )
            }
        }
        availableUpdate?.let { release ->
            val download by vm.updateDownloadState.collectAsStateWithLifecycle()
            AppUpdateFlow(
                release = release,
                download = download,
                onDismiss = vm::dismissUpdate,
                onDownload = vm::downloadUpdate,
            )
        }
    }
}

@Composable
private fun TasksRoute(
    vm: CollabViewModel,
    selectedSession: SessionKey?,
    actions: TasksScreenActions,
    retainedDisplay: androidx.compose.runtime.snapshots.SnapshotStateMap<SessionKey, SessionDisplay>,
) {
    val sessions by vm.sessionListState.collectAsStateWithLifecycle()
    val details by vm.sessionDetails.collectAsStateWithLifecycle()
    val sessionOperations by vm.sessionOperations.collectAsStateWithLifecycle()
    val runtimeStarts by vm.runtimeStarts.collectAsStateWithLifecycle()
    val sendProgress by vm.sendProgress.collectAsStateWithLifecycle()
    val detailLoads by vm.detailLoads.collectAsStateWithLifecycle()
    val modelLoads by vm.modelLoads.collectAsStateWithLifecycle()
    val usageLoads by vm.usageLoads.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val fileSelections by vm.fileSelections.collectAsStateWithLifecycle()
    TasksScreen(
        state = TasksScreenState(sessions, details, detailLoads, modelLoads, sessionOperations,
            sendProgress, drafts, fileSelections, selectedSession, usageLoads, runtimeStarts),
        actions = actions,
        retainedDisplay = retainedDisplay,
    )
}

@Composable
private fun BrowserRoute(
    route: AppRoute.Browser,
    hostOs: String,
    roots: List<String>,
    parentPath: String?,
    knownPaths: List<String>,
    creating: Boolean,
    state: LoadState<dev.pinkcollab.data.Listing>?,
    load: (forceRefresh: Boolean) -> Unit,
    browse: (String) -> Unit,
    select: (String) -> Unit,
    note: (String) -> Unit,
) {
    LaunchedEffect(route.hostId, route.path) { load(false) }
    val ready = state as? LoadState.Ready
    LaunchedEffect(route.hostId, ready?.value) {
        val listing = ready?.value ?: return@LaunchedEffect
        note(listing.path)
        listing.parent?.let(note)
        listing.directories.forEach { note(it.path) }
    }
    DirectoryBrowserScreen(
        state = state,
        hostOs = hostOs,
        routePath = route.path,
        roots = roots,
        parentPath = parentPath,
        knownPaths = knownPaths,
        creating = creating,
        browse = browse,
        select = select,
        refresh = { load(true) },
    )
}

/** Scaffold owns system-bar insets; screens consume only their remaining IME inset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScaffold(
    title: String?,
    hasParentDirectory: Boolean,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            title?.let {
                TopAppBar(
                    title = { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = rememberHapticOnClick(onBack)) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = if (hasParentDirectory) "Parent directory" else "Back",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            content = content,
        )
    }
}
