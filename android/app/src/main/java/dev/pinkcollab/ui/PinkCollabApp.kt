package dev.pinkcollab.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Listing
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.PinkCollabTheme
import dev.pinkcollab.ui.theme.rememberHapticOnClick
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinkCollabApp(vm: CollabViewModel = viewModel()) {
    val repo = vm.repository
    val app by repo.state.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val sessionOperations by vm.sessionOperations.collectAsStateWithLifecycle()
    val detailLoads by vm.detailLoads.collectAsStateWithLifecycle()
    val modelLoads by vm.modelLoads.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val fileSelections by vm.fileSelections.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = AppRouteSaver) { mutableStateOf<AppRoute>(AppRoute.Tasks) }
    var pairHost by rememberSaveable(stateSaver = PairHostSheetStateSaver) {
        mutableStateOf(PairHostSheetState())
    }
    var pairAttemptSequence by rememberSaveable { mutableLongStateOf(0L) }
    var selectedSessionId by rememberSaveable { mutableStateOf("") }
    var startupReady by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, repo) {
        var hasStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (hasStarted) repo.reconnectUnavailableHosts()
                hasStarted = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(repo) {
        awaitStartupReadiness(repo.state)
        startupReady = true
    }

    LaunchedEffect(app.error) {
        app.error?.let {
            snackbar.showSnackbar(it)
            repo.error(null)
        }
    }

    LaunchedEffect(pairHost.attemptId, operations) {
        if (pairHost.attemptId != 0L && OperationKey.PairHost !in operations) {
            pairHost = pairHost.copy(attemptId = 0)
        }
    }

    val secondary = route != AppRoute.Tasks
    val secondaryTitle = when (route) {
        AppRoute.Tasks -> null
        AppRoute.Resources -> "Workspaces"
        is AppRoute.Browser -> "Browse workspace"
    }
    BackHandler(secondary) { route = route.back() }

    PinkCollabTheme {
        Crossfade(
            targetState = startupReady,
            animationSpec = tween(420),
            label = "startupContent",
        ) { ready ->
            if (!ready) {
                StartupLoadingScreen()
            } else Box(Modifier.fillMaxSize().background(Base0)) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    secondaryTitle?.let { title ->
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                IconButton(onClick = rememberHapticOnClick { route = route.back() }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Back",
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
                snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .then(if (secondary) Modifier else Modifier.statusBarsPadding())
                        .navigationBarsPadding(),
                ) {
                    when (val current = route) {
                        AppRoute.Tasks -> TasksScreen(
                            app = app,
                            detailLoads = detailLoads,
                            modelLoads = modelLoads,
                            operations = operations,
                            sessionOperations = sessionOperations,
                            drafts = drafts,
                            fileSelections = fileSelections,
                            selectedSessionId = selectedSessionId,
                            onSessionSelected = { selectedSessionId = it },
                            openResources = { route = AppRoute.Resources },
                            connectHost = { pairHost = PairHostSheetState(visible = true) },
                            loadSession = vm::loadDetail,
                            loadModels = vm::loadModels,
                            onPrompt = vm::sendPrompt,
                            onDraftTextChange = vm::setDraftText,
                            onFileSelected = vm::selectDraftFile,
                            onFileRemoved = vm::removeDraftFile,
                            onCommand = vm::sessionCommand,
                            onRespond = vm::respond,
                            onSelectModel = vm::selectModel,
                            onSetThinkingLevel = vm::setThinkingLevel,
                            onLoadSavedHistory = vm::loadSavedHistory,
                            onLoadEarlier = vm::loadEarlierHistory,
                        )

                        AppRoute.Resources -> ResourcesScreen(
                            app = app,
                            hostBusy = { OperationKey.Host(it) in operations },
                            browse = { hostId, path -> route = AppRoute.Browser(hostId, path) },
                            pair = { pairHost = PairHostSheetState(visible = true) },
                            refresh = { hostId ->
                                if (app.hosts[hostId]?.connection is ConnectionState.Online) {
                                    vm.run(OperationKey.Host(hostId)) { repo.refreshHost(hostId) }
                                } else {
                                    repo.requestReconnect(hostId)
                                }
                            },
                            forget = { hostId -> vm.run(OperationKey.Host(hostId)) { repo.forget(hostId); vm.removeHostDrafts(hostId) } },
                        )

                        is AppRoute.Browser -> BrowserRoute(
                            route = current,
                            hostName = app.hosts[current.hostId]?.paired?.host?.name.orEmpty(),
                            creating = OperationKey.CreateTask(current.hostId, current.path) in operations,
                            load = { forceRefresh -> repo.listing(current.hostId, current.path, forceRefresh) },
                            prefetch = { listing ->
                                repo.prefetchListings(current.hostId, listing.directories.map { it.path })
                            },
                            browse = { path -> route = current.copy(path = path) },
                            select = { path ->
                                val operation = OperationKey.CreateTask(current.hostId, path)
                                vm.run(operation, "Unable to create session") {
                                    val session = repo.create(current.hostId, path)
                                    selectedSessionId = session.id
                                    route = AppRoute.Tasks
                                    vm.loadDetail(session)
                                    vm.run(OperationKey.Host(current.hostId)) { repo.refreshHost(current.hostId) }
                                }
                            },
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
                    vm.run(
                        key = OperationKey.PairHost,
                        errorMessage = "Unable to connect host",
                        onError = { message ->
                            if (pairHost.attemptId == attemptId) {
                                pairHost = pairHost.copy(error = message, attemptId = 0)
                            }
                        },
                    ) {
                        repo.pair(url, token)
                        if (pairHost.attemptId == attemptId) {
                            pairHost = PairHostSheetState()
                            route = AppRoute.Resources
                        }
                    }
                },
            )
            }
        }
    }
}

@Composable
private fun BrowserRoute(
    route: AppRoute.Browser,
    hostName: String,
    creating: Boolean,
    load: suspend (forceRefresh: Boolean) -> Listing,
    prefetch: (Listing) -> Unit,
    browse: (String) -> Unit,
    select: (String) -> Unit,
) {
    var retry by rememberSaveable(route.hostId, route.path) { mutableIntStateOf(0) }
    val browserState by produceState<DirectoryBrowserState>(
        initialValue = DirectoryBrowserState.Loading,
        route.hostId,
        route.path,
        retry,
    ) {
        value = DirectoryBrowserState.Loading
        value = try {
            val loaded = load(retry > 0)
            prefetch(loaded)
            DirectoryBrowserState.Ready(loaded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DirectoryBrowserState.Failed(error.message ?: "Unable to read this directory")
        }
    }
    DirectoryBrowserScreen(
        state = browserState,
        hostName = hostName,
        creating = creating,
        browse = browse,
        select = select,
        retry = { retry++ },
    )
}
