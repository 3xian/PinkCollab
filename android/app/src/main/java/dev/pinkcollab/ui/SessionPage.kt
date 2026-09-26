package dev.pinkcollab.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import io.noties.markwon.Markwon

@Composable
internal fun SessionPage(
    state: SessionPageState,
    onAction: (SessionAction) -> Unit,
) {
    val load = state.detail
    val host = state.host
    val draft = state.draft
    val selectingFiles = state.selectingFiles
    val activity = state.activity
    val modelState = state.model
    val onRetry: () -> Unit = { onAction(SessionAction.Retry) }
    val onPrompt: () -> Unit = { onAction(SessionAction.Send) }
    val onDraftTextChange: (String) -> Unit = { onAction(SessionAction.DraftChanged(it)) }
    val onFileSelected: (Uri) -> Unit = { onAction(SessionAction.FileSelected(it)) }
    val onFileRemoved: (String) -> Unit = { onAction(SessionAction.FileRemoved(it)) }
    val onCommand: (SessionUserCommand) -> Unit = { onAction(SessionAction.Command(it)) }
    val onRespond: (AttentionResponse) -> Unit = { onAction(SessionAction.Respond(it)) }
    val onLoadModels: (Boolean) -> Unit = { onAction(SessionAction.LoadModels(it)) }
    val onSelectModel: (ModelInfo) -> Unit = { onAction(SessionAction.SelectModel(it)) }
    val onSetThinkingLevel: (String) -> Unit = { onAction(SessionAction.SetThinkingLevel(it)) }
    val onLoadSavedHistory: () -> Unit = { onAction(SessionAction.LoadSavedHistory) }
    val onLoadEarlier: () -> Unit = { onAction(SessionAction.LoadEarlierHistory) }
    when (load) {
        LoadState.Loading -> {
            TimelineLoadingState()
            return
        }
        is LoadState.Failed -> {
            EmptyState("Could not load this session", load.message, "Retry", onRetry)
            return
        }
        is LoadState.Ready -> Unit
    }
    val detail = load.value
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val session = detail.session
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
    var showSavedHistory by rememberSaveable(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.runtimeGeneration) { showSavedHistory = false }
    val controls = sessionControls(detail, host, draft, selectingFiles, activity, showSavedHistory)
    val attached = controls.attached
    val historyMode = controls.historyMode
    val visibleItems = visibleSessionItems(detail, showSavedHistory)
    val displayTimeline = remember(visibleItems) { projectSessionTimeline(visibleItems) }
    val timelineState = rememberLazyListState()
    var followTimeline by rememberSaveable(session.id) { mutableStateOf(true) }

    val inputEnabled = controls.inputEnabled
    val canSend = controls.canSend
    var inputFocused by remember { mutableStateOf(false) }
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
        displayTimeline,
        detail.streaming,
        session.attention,
        composerHeightPx,
        followTimeline,
    ) {
        if (!followTimeline) return@LaunchedEffect
        withFrameNanos { }
        val lastItemIndex = timelineState.layoutInfo.totalItemsCount - 1
        if (lastItemIndex >= 0) timelineState.scrollToItem(lastItemIndex)
    }
    val density = LocalDensity.current
    val composerClearance = with(density) { composerHeightPx.toDp() } + 12.dp
    // The scaffold already reserves the navigation bar below the composer, so the keyboard
    // overlap has to be measured from that edge rather than the window bottom: padding by the
    // raw IME inset would lift the composer by a whole navigation bar too much.
    val imeOverlap = WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)
    val composerImePadding = with(density) { imeOverlap.coerceAtLeast(0).toDp() }
    val composerShape = RoundedCornerShape(16.dp)
    val composerFill = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
    val idleBorder = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Purple400.copy(alpha = 0.14f)))
    val composerBorder = if (inputFocused) {
        Brush.linearGradient(listOf(Purple400.copy(alpha = 0.74f), Violet400.copy(alpha = 0.54f)))
    } else {
        idleBorder
    }
    fun Modifier.composerCard(border: Brush) = this
        .shadow(
            elevation = 18.dp,
            shape = composerShape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.62f),
            spotColor = Purple400.copy(alpha = 0.18f),
        )
        .border(width = 1.dp, brush = border, shape = composerShape)
        .clip(composerShape)
        .background(composerFill)
    val placeholder = controls.placeholder

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = timelineState,
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (session.runtimeAttached) item(key = "history-toggle") {
                TextButton(onClick = {
                    showSavedHistory = !showSavedHistory
                    followTimeline = !showSavedHistory
                    if (showSavedHistory) onLoadSavedHistory()
                }, modifier = Modifier.fillMaxWidth(), enabled = historyMode || !activity.history) {
                    Text(if (historyMode) "Back to live" else "Saved history")
                }
            }
            if (detail.nextHistoryCursor != null && (historyMode || !session.runtimeAttached)) item(key = "load-earlier") {
                TextButton(onClick = onLoadEarlier, modifier = Modifier.fillMaxWidth(), enabled = !activity.history) {
                    Text("Load earlier messages")
                }
            }
            if (displayTimeline.isEmpty()) item {
                Text(
                    if (historyMode) "No saved messages on this branch yet" else "Waiting for the agent…",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            items(displayTimeline, key = { it.id }) { item -> DisplayItem(item, markwon) }
            if (!historyMode && detail.streaming.isNotBlank()) item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .timelineBand(tint = Purple400, tintAlpha = 0.055f)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlowDot(Purple400, pulse = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        AgentHeader(detail.model, replying = true)
                        Spacer(Modifier.height(4.dp))
                        Text(detail.streaming, style = MaterialTheme.typography.bodySmall, color = TextHigh)
                    }
                }
            }
            detail.operations.lastOrNull()?.takeIf { it.status != OperationStatus.Succeeded }?.let { receipt -> item(key = "operation-${receipt.commandId}") {
                val text = operationStatusText(receipt)
                Text(text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (receipt.status == OperationStatus.OutcomeUnknown || receipt.status == OperationStatus.Failed) Red400 else TextMid)
            } }
            session.attention?.let { attention -> item { AttentionCard(attention, !activity.inputBusy && attached, onRespond) } }
            item(key = "composer-placeholder") {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(composerClearance + 12.dp)
                        .background(TimelineBandBase),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(composerClearance + 104.dp)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.42f to Color.Black.copy(alpha = 0.08f),
                        0.72f to Color.Black.copy(alpha = 0.54f),
                        1.00f to Color.Black.copy(alpha = 0.86f),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = composerImePadding)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onSizeChanged { composerHeightPx = it.height },
        ) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .composerCard(composerBorder)
                        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        BasicTextField(
                            value = prompt,
                            onValueChange = onDraftTextChange,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 72.dp, max = 180.dp)
                                .onFocusChanged { inputFocused = it.isFocused },
                            enabled = inputEnabled,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = if (inputEnabled) TextHigh else Gray400.copy(alpha = 0.65f),
                            ),
                            cursorBrush = SolidColor(Purple400),
                            maxLines = 5,
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            placeholder,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Gray400.copy(alpha = if (inputEnabled) 0.82f else 0.48f),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = controls.canAttach) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = "Attach files")
                        }
                    }
                    if (selectedFiles.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    fileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.sendProgress?.let { progress ->
                        val status = when (progress) {
                            is SendProgress.Uploading -> "Sending file ${progress.fileIndex} of ${progress.fileCount} · ${progress.fileName}"
                            SendProgress.Submitting -> "Sending message…"
                        }
                        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = TextMid, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = Purple400,
                                trackColor = Color.White.copy(alpha = 0.08f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ComposerModelButton(
                        label = composerModelLabel(detail.model),
                        thinkingLevel = composerThinkingLabel(detail.model),
                        onClick = {
                            showModels = true
                            onLoadModels(true)
                        },
                        enabled = controls.canChooseModel,
                    )
                }
                ComposerRail(
                    actions = composerRailActions(
                        showStart = !session.runtimeAttached,
                        canStart = controls.canStart,
                        canInterrupt = controls.canInterrupt,
                        canExit = controls.canStop,
                        canSend = canSend,
                        sendContentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else Gray400.copy(alpha = 0.58f),
                        sendFill = if (canSend) BrandGradient else SolidColor(Gray400.copy(alpha = 0.16f)),
                        onCommand = onCommand,
                        onExit = { showExitConfirmation = true },
                        onSend = { fileError = null; onPrompt() },
                    ),
                    modifier = Modifier.composerCard(idleBorder),
                )
            }
        }
    }
    if (showModels) {
        ModelPickerSheet(
            state = modelState,
            current = detail.model,
            enabled = !activity.inputBusy,
            dismiss = { showModels = false },
            retry = { onLoadModels(true) },
            select = { model ->
                showModels = false
                onSelectModel(model)
            },
            selectThinkingLevel = { level ->
                showModels = false
                onSetThinkingLevel(level)
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
