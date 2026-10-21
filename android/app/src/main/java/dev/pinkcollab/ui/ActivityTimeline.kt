package dev.pinkcollab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.*

/** One disclosure for a continuous work sequence; only its newest result opens automatically. */
@Composable
internal fun ActivityTimeline(
    group: SessionDisplayItem.ActivityGroup,
    active: Boolean,
    liveOperationIds: Set<String>?,
    onInspect: () -> Unit,
    collapseKey: String? = null,
) {
    // Include the reply in the saveable scope: off-screen lazy items must not restore
    // an expanded disclosure saved before that reply arrived.
    key(active, collapseKey) {
        ActivityTimelineContent(group, active, liveOperationIds, onInspect, collapseKey)
    }
}

@Composable
private fun ActivityTimelineContent(
    group: SessionDisplayItem.ActivityGroup,
    active: Boolean,
    liveOperationIds: Set<String>?,
    onInspect: () -> Unit,
    collapseKey: String?,
) {
    // A turn finishing (or losing its live qualification) returns to a compact summary.
    // Within that phase, manual disclosure choices survive new tools and result updates.
    var expanded by rememberSaveable(group.id, active, collapseKey) { mutableStateOf(active) }
    val enteredSteps = remember(group.id) { HashSet<String>() }
    val focus = group.operations.lastOrNull { it.status == ActivityStatus.Running &&
        (liveOperationIds == null || it.callId in liveOperationIds) } ?: group.operations.lastOrNull()
    val title = if (active) focus?.action ?: "Working on it…" else activityGroupSummary(group.operations)
    val failed = group.operations.count { it.status == ActivityStatus.Failed }
    val unfinished = group.operations.count { it.status == ActivityStatus.Running }
    val running = group.operations.count { active && it.status == ActivityStatus.Running &&
        (liveOperationIds == null || it.callId in liveOperationIds) }
    val titleEffect = if (active) workTextShimmer(SessionColors.tool) else Modifier
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(220), label = "workDisclosure")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("activityTimeline")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse activity" else "Expand activity",
                    onClick = rememberHapticOnClick { onInspect(); expanded = !expanded })
                .semantics {
                    contentDescription = if (expanded) "Collapse activity" else "Expand activity"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active) ThinkingSquares(color = SessionColors.tool, modifier = Modifier.size(16.dp).clearAndSetSemantics { })
            else if (failed > 0 || unfinished > 0) Icon(if (failed > 0) Icons.Outlined.ErrorOutline
                else Icons.Outlined.HourglassEmpty, null, Modifier.size(16.dp),
                tint = if (failed > 0) Red400 else TextMid)
            AnimatedContent(
                targetState = title,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(220)) { -it / 3 })
                },
                label = "currentTimelineAction",
            ) { label ->
                Text(label, modifier = titleEffect.then(
                    if (label != title) Modifier.clearAndSetSemantics { }
                    else if (active) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    else Modifier,
                ), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Default,
                    color = if (active) SessionColors.tool else SessionColors.agent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!expanded && !active) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.clearAndSetSemantics { }) {
                    group.operations.distinctBy { toolIdentity(it.name).activityStage }.take(3).forEach { operation ->
                        Icon(activityIcon(operation), null, Modifier.size(14.dp), tint = SessionColors.tool)
                    }
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp).rotate(rotation), tint = Gray400)
        }
        // Failure and uncertainty remain readable even after automatic folding.
        if (!expanded && (failed > 0 || unfinished > 0)) {
            Text(
                listOfNotNull(if (failed > 0) "$failed failed" else null,
                    if (running > 0) "$running running" else null,
                    if (unfinished > running) "${unfinished - running} last seen running" else null,
                    group.summary.takeIf(String::isNotBlank)).joinToString(" · "),
                Modifier.padding(start = 26.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = if (failed > 0) Red400 else TextMid,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(220)) + expandVertically(tween(300)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(260))) {
            Column(Modifier.fillMaxWidth().animateContentSize(tween(300)).padding(start = 7.dp).drawBehind {
                drawLine(BrandPurple.copy(alpha = 0.25f), Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
            }) {
                group.operations.forEach { operation ->
                    key(operation.id) {
                        val enter = remember(operation.id) { active && enteredSteps.add(operation.id) }
                        ActivityStep(operation, latest = active && operation.id == focus?.id,
                            live = active && (liveOperationIds == null || operation.callId in liveOperationIds),
                            enter = enter, onInspect = onInspect, collapseKey = collapseKey)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityStep(operation: ActivityOperation, latest: Boolean, live: Boolean, enter: Boolean, onInspect: () -> Unit, collapseKey: String?) {
    var previewChoice by rememberSaveable(operation.id, collapseKey) { mutableStateOf<Boolean?>(null) }
    val accent = activityAccent(operation)
    val hasPreview = operation.targets.isNotEmpty() || operation.preview.isNotBlank() || operation.error.isNotBlank()
    val previewExpanded = hasPreview && (previewChoice ?: latest)
    val rotation by animateFloatAsState(if (previewExpanded) 90f else 0f, tween(220), label = "stepDisclosure")
    val titleEffect = if (latest && live && operation.status == ActivityStatus.Running) workTextShimmer(SessionColors.tool) else Modifier
    val entrance = remember(operation.id) { Animatable(if (enter) 0f else 1f) }
    LaunchedEffect(entrance) { if (entrance.value < 1f) entrance.animateTo(1f, tween(260)) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp).animateContentSize(tween(280)).graphicsLayer {
        alpha = entrance.value
        translationY = (1f - entrance.value) * 6.dp.toPx()
    }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("activity-step-${operation.id}")
                .then(if (hasPreview) Modifier.clickable(role = Role.Button, onClick = rememberHapticOnClick {
                    onInspect()
                    previewChoice = !previewExpanded
                }).semantics { stateDescription = if (previewExpanded) "Expanded" else "Collapsed" } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivityToolIcon(operation, live)
            Box(Modifier.weight(1f)) {
                Text(operation.action, titleEffect, style = MaterialTheme.typography.bodyMedium,
                    color = SessionColors.tool, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (hasPreview) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp).rotate(rotation), tint = Gray400)
        }
        AnimatedVisibility(previewExpanded, enter = fadeIn(tween(220)) + expandVertically(tween(280)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(240))) {
            Surface(
                Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 8.dp).testTag("activity-preview-${operation.id}"),
                shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.06f).compositeOver(Base1),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    operation.targets.take(3).forEach { target ->
                        Text(target, style = MaterialTheme.typography.bodySmall, color = accent,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (operation.targets.size > 3) Text("+${operation.targets.size - 3} more",
                        style = MaterialTheme.typography.labelSmall, color = Gray400)
                    if (operation.error.isNotBlank()) Text(operation.error, style = MaterialTheme.typography.bodySmall,
                        color = Red400, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (operation.preview.isNotBlank()) Text(operation.preview, style = MaterialTheme.typography.bodySmall,
                        color = TextMid, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ActivityToolIcon(operation: ActivityOperation, live: Boolean) {
    val description = "${operation.action}, ${activityStatusLabel(operation.status, live)}"
    if (operation.status == ActivityStatus.Running && live) {
        CircularProgressIndicator(Modifier.size(16.dp).semantics { contentDescription = description },
            color = SessionColors.tool, strokeWidth = 1.5.dp)
    } else {
        val icon = if (operation.status == ActivityStatus.Running) Icons.Outlined.HourglassEmpty else activityIcon(operation)
        Icon(icon, description, Modifier.size(16.dp), tint = SessionColors.tool)
    }
}

private fun activityAccent(operation: ActivityOperation): Color = if (operation.status == ActivityStatus.Failed) Red400
else when (toolIdentity(operation.name).activityStage) {
    ActivityStage.Explore -> SessionColors.source
    ActivityStage.Change -> SessionColors.change
    ActivityStage.Execute -> SessionColors.command
    ActivityStage.Other -> Violet400
}

private fun activityIcon(operation: ActivityOperation): ImageVector = if (operation.status == ActivityStatus.Failed) Icons.Outlined.ErrorOutline
else when (toolIdentity(operation.name).activityStage) {
    ActivityStage.Explore -> if (toolIdentity(operation.name).workFamily == ToolFamily.Search) Icons.Outlined.Search else Icons.Outlined.Description
    ActivityStage.Change -> Icons.Outlined.EditNote
    ActivityStage.Execute -> Icons.Outlined.Terminal
    ActivityStage.Other -> Icons.Outlined.Build
}

private fun activityStatusLabel(status: ActivityStatus, live: Boolean): String = when (status) {
    ActivityStatus.Running -> if (live) "Running" else "Last seen running"
    ActivityStatus.Succeeded -> "Completed"
    ActivityStatus.Failed -> "Failed"
}

@Composable
internal fun TimelineWorkingIndicator() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).testTag("timelineWorking"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThinkingSquares(color = SessionColors.tool, modifier = Modifier.size(16.dp).clearAndSetSemantics { })
        Text("Working on it…", modifier = workTextShimmer(SessionColors.tool).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Default, color = SessionColors.tool)
    }
}
