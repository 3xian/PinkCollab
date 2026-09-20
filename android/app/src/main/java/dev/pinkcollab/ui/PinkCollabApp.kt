package dev.pinkcollab.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.pinkcollab.data.Listing
import dev.pinkcollab.ui.theme.GlowBackground
import dev.pinkcollab.ui.theme.PinkCollabTheme
import dev.pinkcollab.ui.theme.Purple200
import dev.pinkcollab.ui.theme.rememberHapticOnClick
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
fun PinkCollabApp(vm: CollabViewModel = viewModel()) {
    val repo = vm.repository
    val app by repo.state.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val detailLoads by vm.detailLoads.collectAsStateWithLifecycle()
    var route by rememberSaveable(stateSaver = AppRouteSaver) { mutableStateOf<AppRoute>(AppRoute.Tasks) }
    var selectedSessionId by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(app.error) {
        app.error?.let {
            snackbar.showSnackbar(it)
            repo.error(null)
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value ->
            runCatching {
                val json = JSONObject(value)
                require(json.getInt("version") == 1) { "Unsupported pairing code version" }
                route = AppRoute.PairHost(json.getString("url"), json.getString("token"))
            }.onFailure { repo.error("Unrecognized PinkCollab pairing code: ${it.message}") }
        }
    }

    val secondary = route != AppRoute.Tasks
    BackHandler(secondary) { route = route.back() }

    PinkCollabTheme {
        Box(Modifier.fillMaxSize()) {
            GlowBackground(Modifier.matchParentSize())
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .statusBarsPadding()
                        .padding(top = if (secondary) 56.dp else 0.dp)
                        .navigationBarsPadding(),
                ) {
                    when (val current = route) {
                        AppRoute.Tasks -> TasksScreen(
                            app = app,
                            detailLoads = detailLoads,
                            operations = operations,
                            selectedSessionId = selectedSessionId,
                            onSessionSelected = { selectedSessionId = it },
                            openResources = { route = AppRoute.Resources },
                            connectHost = { route = AppRoute.PairHost() },
                            loadSession = vm::loadDetail,
                            onPrompt = { session, message, onSent ->
                                vm.run(OperationKey.Session(SessionKey(session.hostId, session.id))) {
                                    repo.command(session.hostId, session.id, "prompt", JSONObject().put("message", message))
                                    onSent()
                                }
                            },
                            onCommand = { session, command ->
                                vm.run(OperationKey.Session(SessionKey(session.hostId, session.id))) {
                                    repo.command(session.hostId, session.id, command)
                                }
                            },
                            onRespond = { session, body ->
                                vm.run(OperationKey.Session(SessionKey(session.hostId, session.id))) {
                                    repo.command(session.hostId, session.id, "respond", body)
                                }
                            },
                            onCycleModel = { session ->
                                vm.run(OperationKey.Session(SessionKey(session.hostId, session.id))) {
                                    repo.cycleModel(session.hostId, session.id)
                                }
                            },
                        )

                        AppRoute.Resources -> ResourcesScreen(
                            app = app,
                            hostBusy = { OperationKey.Host(it) in operations },
                            browse = { hostId, path -> route = AppRoute.Browser(hostId, path) },
                            pair = { route = AppRoute.PairHost() },
                            refresh = { hostId -> vm.run(OperationKey.Host(hostId)) { repo.refreshHost(hostId) } },
                            forget = { hostId -> vm.run(OperationKey.Host(hostId)) { repo.forget(hostId) } },
                        )

                        is AppRoute.Browser -> BrowserRoute(
                            route = current,
                            hostName = app.hosts[current.hostId]?.paired?.host?.name.orEmpty(),
                            load = { repo.listing(current.hostId, current.path) },
                            browse = { path -> route = current.copy(path = path) },
                            select = { path -> route = AppRoute.CreateTask(current.hostId, path) },
                        )

                        is AppRoute.CreateTask -> {
                            val operation = OperationKey.CreateTask(current.hostId, current.cwd)
                            CreateTaskScreen(
                                host = app.hosts[current.hostId],
                                cwd = current.cwd,
                                busy = operation in operations,
                                changeDirectory = { route = AppRoute.Browser(current.hostId, current.cwd) },
                                start = { prompt ->
                                    vm.run(operation, "Unable to create task") {
                                        val session = repo.create(current.hostId, current.cwd, prompt)
                                        selectedSessionId = session.id
                                        route = AppRoute.Tasks
                                        vm.loadDetail(session)
                                        vm.run(OperationKey.Host(current.hostId)) { repo.refreshHost(current.hostId) }
                                    }
                                },
                            )
                        }

                        is AppRoute.PairHost -> PairHostScreen(
                            url = current.url,
                            token = current.token,
                            busy = OperationKey.PairHost in operations,
                            onURL = { route = current.copy(url = it) },
                            onToken = { route = current.copy(token = it) },
                            scan = {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scan the pairing code from the Gateway")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(true),
                                )
                            },
                            pair = {
                                vm.run(OperationKey.PairHost, "Unable to connect host") {
                                    repo.pair(current.url, current.token)
                                    route = AppRoute.Resources
                                }
                            },
                        )
                    }
                }
            }

            if (secondary) {
                IconButton(
                    onClick = rememberHapticOnClick { route = route.back() },
                    modifier = Modifier.statusBarsPadding().padding(start = 12.dp, top = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(28.dp), tint = Purple200)
                }
            }
        }
    }
}

@Composable
private fun BrowserRoute(
    route: AppRoute.Browser,
    hostName: String,
    load: suspend () -> Listing,
    browse: (String) -> Unit,
    select: (String) -> Unit,
) {
    var retry by rememberSaveable(route.hostId, route.path) { mutableIntStateOf(0) }
    val listing by produceState<LoadState<Listing>>(
        initialValue = LoadState.Loading,
        route.hostId,
        route.path,
        retry,
    ) {
        value = LoadState.Loading
        value = try {
            LoadState.Ready(load())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadState.Failed(error.message ?: "Unable to read this directory")
        }
    }
    DirectoryBrowserScreen(
        listing = listing,
        hostName = hostName,
        browse = browse,
        select = select,
        retry = { retry++ },
    )
}
