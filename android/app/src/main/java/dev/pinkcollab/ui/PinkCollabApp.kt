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
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.PinkCollabTheme
import dev.pinkcollab.ui.theme.rememberHapticOnClick
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinkCollabApp(vm: CollabViewModel = viewModel()) {
    val app by vm.appState.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()
    val sessionOperations by vm.sessionOperations.collectAsStateWithLifecycle()
    val sendProgress by vm.sendProgress.collectAsStateWithLifecycle()
    val detailLoads by vm.detailLoads.collectAsStateWithLifecycle()
    val modelLoads by vm.modelLoads.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val fileSelections by vm.fileSelections.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = AppRouteSaver) { mutableStateOf<AppRoute>(AppRoute.Tasks) }
    var pairHost by rememberSaveable(stateSaver = PairHostSheetStateSaver) {
        mutableStateOf(PairHostSheetState())
    }
    var pairAttemptSequence by rememberSaveable { mutableLongStateOf(0L) }
    var selectedSession by rememberSaveable(saver = SelectedSessionSaver) { mutableStateOf<SessionKey?>(null) }
    var startupReady by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        var hasStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (hasStarted) vm.reconnectUnavailableHosts()
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

    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is UiEffect.ShowSnackbar -> launch { snackbar.showSnackbar(effect.message) }
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
                            state = TasksScreenState(app, detailLoads, modelLoads, sessionOperations,
                                sendProgress, drafts, fileSelections, selectedSession),
                            actions = TasksScreenActions(
                                selectSession = { selectedSession = it },
                                openResources = { route = AppRoute.Resources },
                                connectHost = { pairHost = PairHostSheetState(visible = true) },
                                session = vm::onSessionAction,
                            ),
                        )

                        AppRoute.Resources -> ResourcesScreen(
                            app = app,
                            hostBusy = { OperationKey.Host(it) in operations },
                            browse = { hostId, path -> route = AppRoute.Browser(hostId, path) },
                            pair = { pairHost = PairHostSheetState(visible = true) },
                            refresh = vm::refreshHost,
                            forget = vm::forgetHost,
                        )

                        is AppRoute.Browser -> BrowserRoute(
                            route = current,
                            hostName = app.hosts[current.hostId]?.paired?.host?.name.orEmpty(),
                            creating = OperationKey.CreateTask(current.hostId, current.path) in operations,
                            state = directory?.takeIf { it.key == BrowserKey(current.hostId, current.path) }?.state,
                            load = { forceRefresh -> vm.loadDirectory(BrowserKey(current.hostId, current.path), forceRefresh) },
                            browse = { path -> route = current.copy(path = path) },
                            select = { path -> vm.createSession(current.hostId, path) },
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
    }
}

@Composable
private fun BrowserRoute(
    route: AppRoute.Browser,
    hostName: String,
    creating: Boolean,
    state: LoadState<dev.pinkcollab.data.Listing>?,
    load: (forceRefresh: Boolean) -> Unit,
    browse: (String) -> Unit,
    select: (String) -> Unit,
) {
    LaunchedEffect(route.hostId, route.path) { load(false) }
    DirectoryBrowserScreen(
        state = state,
        hostName = hostName,
        creating = creating,
        browse = browse,
        select = select,
        retry = { load(true) },
    )
}
