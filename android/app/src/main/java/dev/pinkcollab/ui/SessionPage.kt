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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.PlatformTextStyle
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
    val controls = sessionControls(detail, host, draft, selectingFiles, activity)
    val attached = controls.attached
    val historyTimeline = remember(detail.historyItems) { projectSessionTimeline(detail.historyItems) }
    val liveTimeline = if (session.runtimeAttached) {
        remember(detail.liveItems) { projectSessionTimeline(detail.liveItems) }
    } else emptyList()
    val hasSavedMessages = historyTimeline.isNotEmpty() || detail.nextHistoryCursor != null
    val timelineState = rememberLazyListState()
    var followTimeline by rememberSaveable(session.id) { mutableStateOf(true) }

    val inputEnabled = controls.inputEnabled
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
        historyTimeline,
        liveTimeline,
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
    val composerShape = RoundedCornerShape(14.dp)
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
    fun Modifier.composerCard(border: Brush? = null) = this
        .shadow(
            elevation = 18.dp,
            shape = composerShape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.62f),
            spotColor = Purple400.copy(alpha = 0.18f),
        )
        .then(if (border == null) Modifier else Modifier.border(width = 1.dp, brush = border, shape = composerShape))
        .clip(composerShape)
        .background(composerFill)
    val placeholder = controls.placeholder

    Box(Modifier.fillMaxSize()) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            typography = SessionTypography,
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = timelineState,
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (detail.nextHistoryCursor != null) item(key = "load-earlier") {
                TextButton(onClick = onLoadEarlier, modifier = Modifier.fillMaxWidth(), enabled = !activity.history) {
                    Text("Load earlier messages")
                }
            }
            items(historyTimeline, key = { "saved:${it.id}" }) { item -> DisplayItem(item, markwon) }
            if (session.runtimeAttached && hasSavedMessages) item(key = "live-divider") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    HorizontalDivider(color = TextMid.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Live updates · saved messages above may repeat",
                        style = PinkCollabTypography.labelMedium,
                        color = TextMid,
                    )
                }
            }
            if (session.runtimeAttached) {
                if (liveTimeline.isEmpty()) item(key = "timeline-empty") {
                    Text(
                        "Waiting for the agent…",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
                items(liveTimeline, key = { "live:${it.id}" }) { item -> DisplayItem(item, markwon) }
            } else if (historyTimeline.isEmpty()) item(key = "timeline-empty") {
                Text(
                    "No saved messages yet",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            if (session.runtimeAttached && detail.streaming.isNotBlank()) item {
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
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onSizeChanged { composerHeightPx = it.height },
        ) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .composerCard(composerBorder),
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
                                .weight(1f)
                                .heightIn(min = 48.dp, max = 180.dp)
                                // Center the first 20sp line beside the 48dp attachment target.
                                .padding(top = 14.dp)
                                .onFocusChanged { inputFocused = it.isFocused },
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
                    state.sendProgress?.let { progress ->
                        val status = when (progress) {
                            is SendProgress.Uploading -> "Sending file ${progress.fileIndex} of ${progress.fileCount} · ${progress.fileName}"
                            SendProgress.Submitting -> "Sending message…"
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ComposerContentInset)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
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
                    ComposerModelButton(
                        label = composerModelLabel(detail.model),
                        thinkingLevel = composerThinkingLabel(detail.model),
                        onClick = { showModels = true },
                        enabled = controls.canChooseModel,
                    )
                }
                ComposerRail(
                    controls = controls,
                    onCommand = onCommand,
                    onExit = { showExitConfirmation = true },
                    onSend = { fileError = null; onPrompt() },
                    modifier = Modifier.composerCard(),
                )
            }
        }
    }
    LaunchedEffect(showModels, attached) {
        if (showModels && attached) onLoadModels(true)
    }
    if (showModels) {
        ModelPickerSheet(
            state = modelState,
            current = detail.model,
            enabled = attached && !activity.inputBusy,
            runtimeAttached = attached,
            runtimeStarting = session.status == SessionStatus.Starting || activity.action,
            canStartRuntime = controls.canChooseModel,
            startRuntime = { onCommand(SessionUserCommand.Start) },
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
