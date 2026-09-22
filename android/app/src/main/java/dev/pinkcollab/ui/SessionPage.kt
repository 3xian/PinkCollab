package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
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
            TimelineLoadingState()
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
    // The scaffold already reserves the navigation bar below the composer, so the keyboard
    // overlap has to be measured from that edge rather than the window bottom: padding by the
    // raw IME inset would lift the composer by a whole navigation bar too much.
    val imeOverlap = WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)
    val composerImePadding = with(density) { imeOverlap.coerceAtLeast(0).toDp() }
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
                bottom = 0.dp,
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
                        Text("OMP · replying", style = MaterialTheme.typography.labelMedium, color = Violet400)
                        Spacer(Modifier.height(4.dp))
                        Text(detail.streaming, style = MaterialTheme.typography.bodySmall, color = TextHigh)
                    }
                }
            }
            session.attention?.let { attention -> item { AttentionCard(attention, !busy && attached, onRespond) } }
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
                    color = TextMid,
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

internal val TimelineBandBase = Color(0xFF0D0A10)

/**
 * Timeline entries form full-width editorial bands. Their flat geometry keeps
 * the conversation continuous while the subtle tint distinguishes speakers
 * and status without competing with the floating composer.
 */
internal fun Modifier.timelineBand(
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

private fun Modifier.userMessageBand(
    container: Color,
    primary: Color,
    secondary: Color,
): Modifier = background(container)
    .background(
        Brush.horizontalGradient(
            listOf(
                primary.copy(alpha = 0.20f),
                Color.Transparent,
                secondary.copy(alpha = 0.10f),
                primary.copy(alpha = 0.06f),
            ),
        ),
    )
    .drawBehind {
        drawLine(
            color = Color.White.copy(alpha = 0.07f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

@Composable
private fun Modifier.runningActivityBackground(
    active: Boolean,
    tint: Color,
): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "runningActivityBackground")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
        ),
        label = "runningActivityBackgroundSweep",
    )
    return drawWithCache {
        val beamWidth = size.width * 0.28f
        val beamSpacing = size.width * 0.48f
        val beam = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                tint.copy(alpha = 0.04f),
                tint.copy(alpha = 0.14f),
                tint.copy(alpha = 0.20f),
                tint.copy(alpha = 0.10f),
                Color.Transparent,
            ),
            startX = 0f,
            endX = beamWidth,
        )
        onDrawBehind {
            val shift = progress.value * beamSpacing
            for (index in -2..3) {
                val left = index * beamSpacing + shift
                rotate(
                    degrees = -12f,
                    pivot = Offset(left + beamWidth / 2f, size.height / 2f),
                ) {
                    translate(left = left, top = -size.height / 2f) {
                        drawRect(brush = beam, size = Size(beamWidth, size.height * 2f))
                    }
                }
            }
        }
    }
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
    val colors = MaterialTheme.colorScheme
    val band = if (isUser) {
        Modifier.userMessageBand(colors.primaryContainer, colors.primary, colors.secondary)
    } else {
        Modifier.timelineBand(tint = Teal300, tintAlpha = 0.026f)
    }
    val contentColor = if (isUser) colors.onPrimaryContainer else TextHigh
    Column(
        Modifier
            .fillMaxWidth()
            .then(band)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (!isUser) {
            Text(
                "Assistant",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = TextHigh,
            )
        }
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
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
    val activityTint = when (group.status) {
        ActivityStatus.Failed -> Red400
        ActivityStatus.Succeeded -> Teal300
        ActivityStatus.Running -> Violet400
    }
    // The projection decides expandability: a group carries a detail kind exactly when it has
    // details, so the view does not re-derive the rule from the stage and status.
    val detailKind = group.detailKind
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = activityTint,
                tintAlpha = if (group.status == ActivityStatus.Running) 0.05f else 0.035f,
            )
            .runningActivityBackground(
                active = group.status == ActivityStatus.Running,
                tint = activityTint,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = activityTint,
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
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
        if (item.details.isNotBlank() && item.details != item.text) {
            TextButton(onClick = rememberHapticOnClick { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text(if (expanded) "Collapse" else "View error") }
            if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
        }
    }
}

@Composable
private fun RawTimelineCard(item: TimelineItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isUser = item.kind == "user"
    val colors = MaterialTheme.colorScheme
    val isDetail = item.kind in listOf("tool", "subagent")
    val detail = item.tool?.let { tool ->
        buildList {
            if (tool.arguments.raw.isNotBlank()) add("Arguments\n${tool.arguments.raw}")
            if (tool.result.isNotBlank()) add("Result\n${tool.result}")
        }.joinToString("\n\n")
    }.orEmpty().ifBlank { item.detail }
    val band = if (isUser) {
        Modifier.userMessageBand(colors.primaryContainer, colors.primary, colors.secondary)
    } else {
        Modifier.timelineBand(
            tint = when (item.kind) {
                "error" -> Red400
                "assistant" -> Teal300
                else -> Gray400
            },
        )
    }
    val contentColor = if (isUser) colors.onPrimaryContainer else TextHigh
    Column(
        Modifier
            .fillMaxWidth()
            .then(band)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!isUser) {
            Text(
                when (item.kind) {
                    "assistant" -> "Assistant"
                    "tool" -> "Tool call"
                    "subagent" -> "Subagent"
                    "error" -> "Error"
                    else -> "Activity"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (item.kind == "error") Red400 else if (item.kind == "assistant") TextHigh else TextMid,
            )
        }
        Text(
            item.text,
            style = if (isDetail) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
        if (isDetail && detail.isNotBlank()) {
            TextButton(
                onClick = rememberHapticOnClick { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Purple200),
            ) {
                Text(if (expanded) "Collapse details" else "Expand details")
            }
            if (expanded) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextMid,
                )
            }
        }
    }
}

@Composable
private fun AttentionCard(attention: Attention, enabled: Boolean, respond: (JSONObject) -> Unit) {

    val attentionColor = statusColor("needs_input")
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
            GlowDot(attentionColor, pulse = true)
            Spacer(Modifier.width(8.dp))
            Text("OMP needs your reply", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = attentionColor)
        }
        Text(attention.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
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
