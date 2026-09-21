package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import org.json.JSONObject

@Composable
internal fun SessionPage(
    state: LoadState<SessionDetail>,
    host: HostState?,
    busy: Boolean,
    onRetry: () -> Unit,
    onPrompt: (String, () -> Unit) -> Unit,
    onCommand: (String) -> Unit,
    onRespond: (JSONObject) -> Unit,
    modelState: LoadState<List<ModelInfo>>?,
    onLoadModels: (Boolean) -> Unit,
    onSelectModel: (ModelInfo) -> Unit,
) {
    when (state) {
        LoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Purple400)
            }
            return
        }
        is LoadState.Failed -> {
            EmptyState("Could not load this task", state.message, "Retry", onRetry)
            return
        }
        is LoadState.Ready -> Unit
    }
    val detail = state.value
    val session = detail.session
    var prompt by rememberSaveable(session.id) { mutableStateOf("") }
    var showModels by rememberSaveable(session.id) { mutableStateOf(false) }
    var showStopConfirmation by rememberSaveable(session.id) { mutableStateOf(false) }
    val attached = session.runtimeAttached && host?.connected == true
    val displayTimeline = remember(detail.timeline) { projectSessionTimeline(detail.timeline) }

    val inputEnabled = attached && !busy && session.attention == null
    val canSend = prompt.isNotBlank() && inputEnabled
    var inputFocused by remember { mutableStateOf(false) }
    var composerHeightPx by remember(session.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val composerClearance = with(density) { composerHeightPx.toDp() } + 12.dp
    val composerShape = RoundedCornerShape(24.dp)
    val composerBorder = if (inputFocused) {
        Brush.linearGradient(listOf(Purple400.copy(alpha = 0.74f), Violet400.copy(alpha = 0.54f)))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Purple400.copy(alpha = 0.14f)))
    }
    val placeholder = when {
        detail.timeline.isEmpty() -> "What should OMP do?"
        session.status == "running" -> "Steer OMP…"
        else -> "Send another prompt…"
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = maxOf(16.dp, composerClearance),
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (displayTimeline.isEmpty()) item {
                Text(
                    "Waiting for the agent…",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            items(displayTimeline, key = { it.id }) { item -> DisplayItem(item) }
            if (detail.streaming.isNotBlank()) item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .timelineBand(tint = Purple400, tintAlpha = 0.055f)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlowDot(Purple400, pulse = true)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("OMP · replying", style = MaterialTheme.typography.labelMedium, color = Purple200)
                        Spacer(Modifier.height(4.dp))
                        Text(detail.streaming, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
                    }
                }
            }
            session.attention?.let { attention -> item { AttentionCard(attention, !busy && attached, onRespond) } }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onSizeChanged { composerHeightPx = it.height }
                .shadow(
                    elevation = 18.dp,
                    shape = composerShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.62f),
                    spotColor = Purple400.copy(alpha = 0.18f),
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ),
                    composerShape,
                )
                .border(width = 1.dp, brush = composerBorder, shape = composerShape)
                .padding(start = 16.dp, end = 8.dp, top = 15.dp, bottom = 8.dp),
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp, max = 144.dp)
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
            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                color = Color.White.copy(alpha = 0.07f),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerActionButton(
                    icon = Icons.Outlined.Tune,
                    label = "Model",
                    onClick = {
                        showModels = true
                        onLoadModels(true)
                    },
                    enabled = attached && !busy,
                    color = Purple200,
                )
                ComposerActionButton(
                    icon = Icons.Outlined.PauseCircleOutline,
                    label = "Interrupt",
                    onClick = { onCommand("interrupt") },
                    enabled = attached && !busy && session.status in listOf("running", "needs_input"),
                    color = TextMid,
                )
                ComposerActionButton(
                    icon = Icons.Outlined.StopCircle,
                    label = "Stop",
                    onClick = { showStopConfirmation = true },
                    enabled = attached && !busy,
                    color = Red400,
                )
                Spacer(Modifier.weight(1f))
                ComposerSendButton(
                    onClick = { onPrompt(prompt) { prompt = "" } },
                    enabled = canSend,
                )
            }
        }
    }
    if (showModels) {
        ModelPickerSheet(
            state = modelState,
            current = detail.model,
            enabled = !busy,
            dismiss = { showModels = false },
            retry = { onLoadModels(true) },
            select = { model ->
                showModels = false
                onSelectModel(model)
            },
        )
    }
    if (showStopConfirmation) {
        StopConfirmationDialog(
            dismiss = { showStopConfirmation = false },
            confirm = {
                showStopConfirmation = false
                onCommand("stop")
            },
        )
    }
}

@Composable
private fun StopConfirmationDialog(
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Outlined.StopCircle, null, tint = Red400) },
        title = { Text("Stop this task?") },
        text = {
            Text(
                "This ends the current OMP process. The conversation will remain visible, but the action cannot be undone.",
                color = TextMid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = rememberHapticOnClick(confirm),
                colors = ButtonDefaults.textButtonColors(contentColor = Red400),
            ) { Text("Stop task", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(
                onClick = rememberHapticOnClick(dismiss),
                colors = ButtonDefaults.textButtonColors(contentColor = TextMid),
            ) { Text("Keep running") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    )
}

@Composable
private fun ComposerActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
) {
    TextButton(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 5.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = Gray400.copy(alpha = 0.34f),
        ),
    ) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ComposerSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    TextButton(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = Modifier
            .height(40.dp)
            .then(if (enabled) Modifier.background(BrandGradient, shape) else Modifier),
        shape = shape,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContentColor = Gray400.copy(alpha = 0.34f),
        ),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text("Send", maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    state: LoadState<List<ModelInfo>>?,
    current: ModelInfo?,
    enabled: Boolean,
    dismiss: () -> Unit,
    retry: () -> Unit,
    select: (ModelInfo) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Ctrl+P models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mirrors OMP's quick-switch cycle",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
                current?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Current · ${it.provider} · ${it.name}", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
            when (state) {
                null, LoadState.Loading -> Box(
                    Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Purple400) }
                is LoadState.Failed -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = rememberHapticOnClick(retry)) { Text("Retry") }
                }
                is LoadState.Ready -> if (state.value.isEmpty()) {
                    Text(
                        "No Ctrl+P models are configured.",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        color = TextMid,
                    )
                } else {
                    val selectedIndex = state.value.indexOfFirst { model ->
                        current?.let { it.provider == model.provider && it.id == model.id } == true
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        itemsIndexed(
                            state.value,
                            key = { _, model -> "${model.role}/${model.provider}/${model.id}" },
                        ) { index, model ->
                            val selected = index == selectedIndex
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled) { select(model) }
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        model.role?.let { role ->
                                            Surface(
                                                color = Purple400.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(7.dp),
                                            ) {
                                                Text(
                                                    role.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Purple200,
                                                )
                                            }
                                            Spacer(Modifier.width(9.dp))
                                        }
                                        Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        buildString {
                                            append(model.provider)
                                            append(" · ")
                                            append(model.id)
                                            model.thinkingLevel?.let {
                                                append(" · ")
                                                append(it)
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMid,
                                    )
                                }
                                RadioButton(
                                    selected = selected,
                                    onClick = { if (enabled) select(model) },
                                    enabled = enabled,
                                    colors = RadioButtonDefaults.colors(selectedColor = Purple400),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TimelineBandBase = Color(0xFF0D0A10)

/**
 * Timeline entries form full-width editorial bands. Their flat geometry keeps
 * the conversation continuous while the subtle tint distinguishes speakers
 * and status without competing with the floating composer.
 */
private fun Modifier.timelineBand(
    tint: Color = Purple400,
    tintAlpha: Float = 0.025f,
): Modifier = background(TimelineBandBase)
    .background(
        Brush.horizontalGradient(
            listOf(
                tint.copy(alpha = tintAlpha),
                Color.Transparent,
            ),
        ),
    )
    .drawBehind {
        drawLine(
            color = Color.White.copy(alpha = 0.055f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

@Composable
private fun DisplayItem(item: SessionDisplayItem) {
    when (item) {
        is SessionDisplayItem.Message -> MessageCard(item)
        is SessionDisplayItem.ActivityGroup -> ActivityGroupCard(item)
        is SessionDisplayItem.Error -> ErrorCard(item)
        is SessionDisplayItem.Raw -> RawTimelineCard(item.item)
    }
}

@Composable
private fun MessageCard(item: SessionDisplayItem.Message) {
    val isUser = item.role == "user"
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = if (isUser) Purple400 else Violet400,
                tintAlpha = if (isUser) 0.075f else 0.026f,
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(if (isUser) "You" else "Assistant", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isUser) Purple200 else Violet400)
        Text(item.text, style = MaterialTheme.typography.bodyLarge, color = TextHigh)
    }
}

@Composable
private fun ActivityGroupCard(group: SessionDisplayItem.ActivityGroup) {

    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val title = when (group.stage) {
        ActivityStage.Explore -> "Exploring · ${group.operationCount} operations"
        ActivityStage.Change -> if (group.files.isNotEmpty()) {
            "Editing · ${group.files.size} files"
        } else {
            "Editing · ${group.operationCount} operations"
        }
        ActivityStage.Execute -> when (group.status) {
            ActivityStatus.Running -> "Verifying"
            ActivityStatus.Succeeded -> "✓ Verified"
            ActivityStatus.Failed -> "Verification failed"
        }
    }
    // The projection decides expandability: a group carries a detail kind exactly when it has
    // details, so the view does not re-derive the rule from the stage and status.
    val detailKind = group.detailKind
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = when (group.status) {
                    ActivityStatus.Failed -> Red400
                    ActivityStatus.Succeeded -> Teal300
                    else -> Purple400
                },
                tintAlpha = 0.035f,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = when (group.status) {
                ActivityStatus.Failed -> Red400
                ActivityStatus.Succeeded -> Teal300
                else -> TextHigh
            },
        )
        if (group.summary.isNotBlank()) Text(group.summary, style = MaterialTheme.typography.bodySmall, color = TextMid)
        if (detailKind != null) {
            TextButton(onClick = rememberHapticOnClick { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Purple200)) {
                Text(if (expanded) "Collapse" else detailKind.action)
            }
            if (expanded) Text(group.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
        }
    }
}

private val ActivityDetailKind.action: String
    get() = when (this) {
        ActivityDetailKind.Diff -> "View diff"
        ActivityDetailKind.Content -> "View content"
        ActivityDetailKind.Changes -> "View changes"
        ActivityDetailKind.Error -> "View error"
    }

@Composable
private fun ErrorCard(item: SessionDisplayItem.Error) {

    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = Red400,
                tintAlpha = 0.065f,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Error", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Red400)
        Text(item.text, color = TextHigh)
        if (item.details.isNotBlank() && item.details != item.text) {
            TextButton(onClick = rememberHapticOnClick { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text(if (expanded) "Collapse" else "View error") }
            if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
        }
    }
}

@Composable
private fun RawTimelineCard(item: TimelineItem) {

    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isDetail = item.kind in listOf("tool", "subagent")
    val detail = item.tool?.let { tool ->
        buildList {
            if (tool.arguments.raw.isNotBlank()) add("Arguments\n${tool.arguments.raw}")
            if (tool.result.isNotBlank()) add("Result\n${tool.result}")
        }.joinToString("\n\n")
    }.orEmpty().ifBlank { item.detail }
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = when (item.kind) {
                    "error" -> Red400
                    "user" -> Purple400
                    else -> Violet400
                },
                tintAlpha = if (item.kind == "user") 0.075f else 0.025f,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(when (item.kind) { "user" -> "You"; "assistant" -> "Assistant"; "tool" -> "Tool call"; "subagent" -> "Subagent"; "error" -> "Error"; else -> "Activity" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = when (item.kind) { "user" -> Purple200; "error" -> Red400; else -> Violet400 })
        Text(item.text, style = if (isDetail) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, color = TextHigh)
        if (isDetail && detail.isNotBlank()) { TextButton(onClick = rememberHapticOnClick { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Purple200)) { Text(if (expanded) "Collapse details" else "Expand details") }; if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid) }
    }
}

@Composable
private fun AttentionCard(attention: Attention, enabled: Boolean, respond: (JSONObject) -> Unit) {

    var answer by rememberSaveable(attention.id) { mutableStateOf("") }
    fun response() = JSONObject().put("id", attention.id)
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = Purple400,
                tintAlpha = 0.10f,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowDot(Purple400, pulse = true)
            Spacer(Modifier.width(8.dp))
            Text("OMP needs your reply", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Purple200)
        }
        Text(attention.text, color = TextHigh)
        when (attention.type) {
            "select" -> attention.options.forEach { option ->
                OutlinedButton(onClick = rememberHapticOnClick { respond(response().put("value", option)) }, enabled = enabled) { Text(option) }
            }
            "confirm" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = rememberHapticOnClick { respond(response().put("confirmed", false)) }, enabled = enabled) { Text("Decline") }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(onClick = { respond(response().put("confirmed", true)) }, enabled = enabled) { Text("Confirm") }
            }
            else -> {
                OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, minLines = if (attention.type == "editor") 4 else 1, modifier = Modifier.fillMaxWidth(), enabled = enabled, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple400, unfocusedBorderColor = Color.White.copy(alpha = 0.14f), cursorColor = Purple400))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PrimaryButton(onClick = { respond(response().put("value", answer)) }, enabled = enabled) { Text("Submit") }
                }
            }
        }
        TextButton(onClick = rememberHapticOnClick { respond(response().put("cancelled", true)) }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = TextMid)) { Text("Cancel") }
    }
}
