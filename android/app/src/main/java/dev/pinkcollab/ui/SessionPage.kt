package dev.pinkcollab.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.PlatformTextStyle
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SessionPage(
    state: SessionPageState,
    onAction: (SessionAction) -> Unit,
    onApplyModelSettings: (ModelSettingsChanges) -> Boolean,
    isActive: Boolean = true,
    loadToolDetails: suspend (String, String?) -> ToolDetailPage = { _, _ -> error("Tool details unavailable") },
) {
    val load = state.detail
    val host = state.host.takeIf { load is LoadState.Ready && load.value.snapshotToken != null }
    val draft = state.draft
    val selectingFiles = state.selectingFiles
    val activity = state.activity
    val modelState = state.model
    val onPrompt: () -> Unit = { onAction(SessionAction.Send) }
    val onDraftTextChange: (String) -> Unit = { onAction(SessionAction.DraftChanged(it)) }
    val onFileSelected: (Uri) -> Unit = { onAction(SessionAction.FileSelected(it)) }
    val onFileRemoved: (String) -> Unit = { onAction(SessionAction.FileRemoved(it)) }
    val onCommand: (SessionUserCommand) -> Unit = { onAction(SessionAction.Command(it)) }
    val onRespond: (AttentionResponse) -> Unit = { onAction(SessionAction.Respond(it)) }
    val onLoadModels: (Boolean) -> Unit = { onAction(SessionAction.LoadModels(it)) }
    // Keep the composer mounted while the selected session's details arrive.
    // The summary supplies identity only; it does not enable runtime actions.
    val detail = (load as? LoadState.Ready)?.value ?: SessionDetail(
        session = requireNotNull(state.summary),
        savedHistory = SavedHistory.Loading,
    )
    val context = LocalContext.current
    val renderer = remember(context, detail.session.hostId, detail.session.id) {
        SessionMarkdownRenderer(createSessionMarkwon(context))
    }
    val session = detail.session
    val currentDetailLoader by rememberUpdatedState(loadToolDetails)
    val toolDetails = remember(session.hostId, session.id, session.generation) {
        ToolDetailsController { callId, cursor -> currentDetailLoader(callId, cursor) }
    }
    val prompt = draft.text
    val selectedFiles = draft.files
    var fileError by remember(session.id) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileError = null
        var count = selectedFiles.size
        val seen = selectedFiles.map { it.uri }.toMutableSet()
        for (uri in uris) {
            if (!seen.add(uri.toString())) continue
            if (count >= 5) {
                fileError = "Up to 5 files per message"
                break
            }
            count++
            onFileSelected(uri)
        }
    }
    var showModels by rememberSaveable(session.id) { mutableStateOf(false) }
    var showExitConfirmation by rememberSaveable(session.id) { mutableStateOf(false) }
    val controls = sessionControls(detail, host, draft, selectingFiles, activity)
    val attached = controls.attached
    val savedHistory = detail.savedHistory
    val historyItems = state.historyItems ?: savedHistory.items
    val liveItems = if (session.runtimeAttached) detail.liveItems else emptyList()
    val conversation = remember(historyItems, liveItems) { conversationTimeline(historyItems, liveItems) }
    val conversationDisplay = remember(conversation) { projectSessionTimeline(conversation) }
    val liveOperationIds = remember(liveItems) { liveItems.filter { it.tool?.completed == false }.map { it.id }.toSet() }
    val savedTodo = remember(historyItems) { projectSessionTodo(historyItems) }
    val liveTodo = remember(liveItems) { projectSessionTodo(liveItems) }
    val todo = latestSessionTodo(savedTodo, liveTodo)
    var todoExpanded by rememberSaveable(session.hostId, session.id) { mutableStateOf(false) }
    LaunchedEffect(todo == null) { if (todo == null) todoExpanded = false }
    BackHandler(todoExpanded && isActive) { todoExpanded = false }
    val historyError = sessionHistoryError(detail, state.host, state.refreshError)
    val awaitingHistory = historyError == null &&
        (savedHistory == SavedHistory.Loading || savedHistory == SavedHistory.Failed) && conversationDisplay.isEmpty() &&
        detail.streaming.isBlank() && session.attention == null
    val contentReady = load is LoadState.Ready && !awaitingHistory
    // Retained content starts visible; refreshes never reset a completed entrance.
    val contentOpacity = remember(session.hostId, session.id) {
        Animatable(if (contentReady) 1f else 0f)
    }
    LaunchedEffect(contentReady, contentOpacity) {
        if (contentReady && contentOpacity.value < 1f) contentOpacity.animateTo(1f, tween(300))
    }
    // Loading is presentation only; subscription readiness still gates runtime actions.
    val workStatus = when {
        load == LoadState.Loading -> SessionWorkStatus(WorkStatusKind.Loading, "Loading conversation")
        load !is LoadState.Ready -> null
        detail.snapshotToken == null && state.host?.connected == true ->
            SessionWorkStatus(WorkStatusKind.Loading, "Loading conversation")
        else -> {
            val current = remember(session, detail.liveItems, detail.streaming.isNotBlank(),
                state.host?.connection, state.sendProgress) {
                sessionWorkStatus(detail, state.host, state.sendProgress)
            }
            if (awaitingHistory &&
                (current.kind == WorkStatusKind.Ready || current.kind == WorkStatusKind.History)) {
                SessionWorkStatus(WorkStatusKind.Loading, "Loading conversation")
            } else current
        }
    }
    val timelineState = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    val historyPullState = rememberPullToRefreshState()
    var followTimeline by rememberSaveable(session.id) { mutableStateOf(true) }
    val canLoadEarlier = isActive && savedHistory.nextCursor != null &&
        host?.connected == true && !activity.history
    val onLoadEarlier: () -> Boolean = {
        if (canLoadEarlier) {
            followTimeline = false
            onAction(SessionAction.LoadEarlierHistory)
            true
        } else false
    }
    LaunchedEffect(isActive) {
        if (isActive) followTimeline = true
    }

    val inputEnabled = controls.inputEnabled
    var composerHeightPx by remember(session.id) { mutableIntStateOf(0) }
    LaunchedEffect(timelineState) {
        snapshotFlow {
            (if (timelineState.isScrollInProgress) 1 else 0) or
                (if (timelineState.lastScrolledBackward) 2 else 0) or
                (if (timelineState.canScrollForward) 4 else 0)
        }.collect { scrollFlags ->
            if (scrollFlags and 3 == 3) {
                followTimeline = false
            } else if (scrollFlags and 4 == 0) {
                followTimeline = true
            }
        }
    }
    LaunchedEffect(
        isActive,
        conversationDisplay,
        detail.streaming,
        session.attention,
        composerHeightPx,
        followTimeline,
    ) {
        if (!isActive || !followTimeline) return@LaunchedEffect
        withFrameNanos { }
        val lastItemIndex = timelineState.layoutInfo.totalItemsCount - 1
        if (lastItemIndex >= 0) timelineState.scrollToItem(lastItemIndex)
    }
    val density = LocalDensity.current
    val composerClearance = with(density) { composerHeightPx.toDp() } + 12.dp
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    // IME insets animate every frame. Moving the composer during placement keeps that
    // animation from remeasuring its text field, buttons, and attachment chips.
    val keyboardOffset = Modifier.offset {
        val overlap = (imeInsets.getBottom(this) - navigationInsets.getBottom(this))
            .coerceAtLeast(0)
        IntOffset(0, -overlap)
    }
    val composerShape = RoundedCornerShape(14.dp)
    val composerFill = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
    fun Modifier.composerCard() = this
        .shadow(
            elevation = 18.dp,
            shape = composerShape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.62f),
            spotColor = Purple400.copy(alpha = 0.18f),
        )
        .clip(composerShape)
        .background(composerFill)
    val placeholder = controls.placeholder

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stackComposerControls = maxWidth < 360.dp * density.fontScale
        val todoHeaderHeight = todoPanelHeaderHeight()
        val todoClearance = if (todo != null) todoHeaderHeight + 12.dp else 0.dp
        val keyboardHeight = with(density) {
            (imeInsets.getBottom(this) - navigationInsets.getBottom(this)).coerceAtLeast(0).toDp()
        }
        val panelMaxHeight = (maxHeight - composerClearance - keyboardHeight - 12.dp).coerceAtLeast(todoHeaderHeight)
        if (load is LoadState.Ready) MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            typography = SessionTypography,
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentOpacity.value }
                .pullToRefresh(
                state = historyPullState,
                isRefreshing = activity.history,
                enabled = canLoadEarlier,
                onRefresh = { onLoadEarlier() },
            )) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("sessionTimeline").semantics {
                        if (canLoadEarlier) {
                            customActions = listOf(CustomAccessibilityAction("Load earlier messages", onLoadEarlier))
                        }
                    },
                    state = timelineState,
                    contentPadding = PaddingValues(
                        top = todoClearance + 8.dp,
                        // Keeps the last timeline item clear of the floating composer card.
                        bottom = composerClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(conversationDisplay, key = { it.id }, contentType = { it::class }) { item ->
                        val running = item is SessionDisplayItem.ActivityGroup && item.operations.any { it.callId in liveOperationIds }
                        DisplayItem(item, renderer, liveActivity = running && host?.connected == true &&
                            session.status == SessionStatus.Running, toolDetails = toolDetails)
                    }
                    if (session.runtimeAttached && detail.streaming.isNotBlank()) item(key = "streaming") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .timelineBand(tint = Purple400, tintAlpha = 0.055f)
                                .padding(12.dp),
                        ) {
                            AgentHeader(detail.model, replying = true)
                            Spacer(Modifier.height(4.dp))
                            TimelineMessageBody(detail.streaming) { _ ->
                                Text(detail.streaming, style = MaterialTheme.typography.bodySmall, color = TextHigh)
                            }
                        }
                    }
                    detail.operations.lastOrNull()?.let { receipt ->
                        operationStatusText(receipt)?.let { text ->
                            item(key = "operation-${receipt.commandId}") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ) {
                                    Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    session.attention?.let { attention -> item(key = "attention") { AttentionCard(attention, !activity.inputBusy && attached, onRespond) } }
                    if (historyError != null) item(key = "history-failed") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(historyError, color = TextMid,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // A separate end anchor reaches the bottom even when the final message is taller than the viewport.
                    item(key = "timeline-end") { Spacer(Modifier.height(1.dp)) }
                }
                if (savedHistory.nextCursor != null || activity.history) {
                    PullToRefreshDefaults.Indicator(
                        state = historyPullState,
                        isRefreshing = activity.history,
                        modifier = Modifier.align(Alignment.TopCenter)
                            .offset(y = todoClearance).testTag("historyPullIndicator"),
                        color = BrandPink,
                    )
                }
            }
        }
        if (load is LoadState.Failed) {
            EmptyState("Could not load this session", load.message,
                modifier = Modifier.padding(bottom = composerClearance))
        } else if (load == LoadState.Loading || awaitingHistory) {
            TimelineLoadingState(Modifier.padding(bottom = composerClearance)
                .testTag(if (load == LoadState.Loading) "sessionLoading" else "historyLoading"))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .then(keyboardOffset)
                .fillMaxWidth()
                .height(composerClearance + 32.dp)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        // Reach the work-status strip already dimmed, then fade to black below it.
                        (32.dp / (composerClearance + 32.dp)) to Color.Black.copy(alpha = 0.60f),
                        1.00f to Color.Black,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .then(keyboardOffset)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onSizeChanged { composerHeightPx = it.height },
        ) {
            workStatus?.let { SessionWorkStatusLine(it) }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .composerCard()
                        .testTag("sessionComposer"),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    val composerTextStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = ComposerContentInset),
                        verticalAlignment = Alignment.Top,
                    ) {
                        BasicTextField(
                            value = prompt,
                            onValueChange = onDraftTextChange,
                            modifier = Modifier
                                .testTag("sessionInput")
                                .weight(1f)
                                .heightIn(min = 48.dp, max = 180.dp)
                                // Center the first 20sp line beside the 48dp attachment target.
                                .padding(top = 14.dp)
                                .onFocusChanged {
                                    if (it.isFocused) todoExpanded = false
                                },
                            enabled = inputEnabled,
                            textStyle = composerTextStyle.copy(
                                color = if (inputEnabled) TextHigh else Gray400.copy(alpha = 0.65f),
                            ),
                            cursorBrush = SolidColor(Purple400),
                            maxLines = 5,
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            placeholder,
                                            style = composerTextStyle,
                                            color = Gray400.copy(alpha = if (inputEnabled) 0.82f else 0.48f),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        IconButton(
                            onClick = rememberHapticOnClick { picker.launch(arrayOf("*/*")) },
                            enabled = controls.canAttach,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = "Attach files",
                                modifier = Modifier.size(20.dp),
                                tint = if (controls.canAttach) TextMid else Gray400.copy(alpha = 0.34f),
                            )
                        }
                    }
                    if (selectedFiles.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = ComposerContentInset)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            selectedFiles.forEach { file ->
                                InputChip(
                                    selected = true,
                                    enabled = inputEnabled,
                                    onClick = { onFileRemoved(file.id) },
                                    label = { Text(file.name, maxLines = 1) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove ${file.name}", modifier = Modifier.size(14.dp)) },
                                )
                            }
                        }
                    }
                    fileError?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = ComposerContentInset),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Box(Modifier.fillMaxWidth()) {
                        // Use the page width: a subcomposed layout cannot participate in the rail's intrinsic-height pass.
                        if (stackComposerControls) {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                ComposerModelButton(
                                    label = composerModelLabel(detail.model),
                                    thinkingLevel = composerThinkingLabel(detail.model),
                                    onClick = { showModels = true },
                                    enabled = controls.canChooseModel,
                                )
                                ComposerFastSwitch(
                                    checked = detail.model?.fastModeEnabled == true,
                                    active = detail.model?.fastModeActive == true,
                                    enabled = controls.canChooseModel,
                                    onCheckedChange = { onApplyModelSettings(ModelSettingsChanges(null, null, it)) },
                                )
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ComposerModelButton(
                                    label = composerModelLabel(detail.model),
                                    thinkingLevel = composerThinkingLabel(detail.model),
                                    onClick = { showModels = true },
                                    enabled = controls.canChooseModel,
                                    modifier = Modifier.weight(1f),
                                )
                                ComposerFastSwitch(
                                    checked = detail.model?.fastModeEnabled == true,
                                    active = detail.model?.fastModeActive == true,
                                    enabled = controls.canChooseModel,
                                    onCheckedChange = { onApplyModelSettings(ModelSettingsChanges(null, null, it)) },
                                )
                            }
                        }
                    }
                }
                ComposerRail(
                    controls = controls,
                    onCommand = onCommand,
                    onExit = { showExitConfirmation = true },
                    onSend = { fileError = null; onPrompt() },
                    modifier = Modifier.align(Alignment.Bottom),
                )
            }
        }
        if (todo != null) {
            AnimatedVisibility(todoExpanded, modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().height(panelMaxHeight).testTag("todoDismissArea")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        todoExpanded = false
                    })
            }
            key(session.hostId, session.id) {
                FloatingTodoPanel(todo, live = host?.connected == true && session.status == SessionStatus.Running &&
                    liveTodo is SessionTodo.Snapshot,
                    expanded = todoExpanded, onExpandedChange = { todoExpanded = it },
                    maxHeight = panelMaxHeight.coerceAtMost(maxHeight * 0.65f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
    LaunchedEffect(showModels, attached, session.generation) {
        if (showModels && attached) onLoadModels(false)
    }
    if (showModels) {
        ModelPickerSheet(
            state = modelState,
            usageState = state.usage,
            loadUsage = { onAction(SessionAction.LoadUsage) },
            current = detail.model,
            enabled = attached && !activity.inputBusy,
            runtimeAttached = attached,
            runtimeStarting = session.status == SessionStatus.Starting,
            canStartRuntime = controls.canChooseModel,
            startRuntime = { onCommand(SessionUserCommand.Start) },
            runtimeStartAttempt = state.runtimeStart,
            dismiss = { showModels = false },
            retry = { onLoadModels(true) },
            refresh = { onLoadModels(true) },
            apply = { changes ->
                if (onApplyModelSettings(changes)) showModels = false
            },
        )
    }
    if (showExitConfirmation) {
        ExitConfirmationDialog(
            dismiss = { showExitConfirmation = false },
            confirm = {
                showExitConfirmation = false
                onCommand(SessionUserCommand.Stop)
            },
        )
    }
}
