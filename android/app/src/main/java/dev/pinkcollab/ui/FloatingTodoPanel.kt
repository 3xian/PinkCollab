package dev.pinkcollab.ui

import dev.pinkcollab.data.TodoStatus

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.*


internal val TodoPanelCollapsedHeight = 40.dp

@Composable
internal fun todoPanelHeaderHeight(): Dp =
    TodoPanelCollapsedHeight + 40.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)

/** The header reserves space once; expanding the bounded overlay never shifts messages. */
@Composable
internal fun FloatingTodoPanel(
    item: TodoPlan,
    live: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val tasks = item.phases.flatMap { it.tasks }
    val closed = tasks.count { it.status == TodoStatus.Completed || it.status == TodoStatus.Abandoned }
    val settled = tasks.isNotEmpty() && closed == tasks.size
    val focus = tasks.firstOrNull { it.status == TodoStatus.Active }
        ?: tasks.firstOrNull { it.status == TodoStatus.Pending }
        ?: tasks.firstOrNull { it.status == TodoStatus.Blocked }
    var previousSettled by remember { mutableStateOf(settled) }
    var completionFlash by remember { mutableStateOf(false) }
    val changeExpanded by rememberUpdatedState(onExpandedChange)
    LaunchedEffect(settled) {
        val celebrate = settled && !previousSettled
        previousSettled = settled
        completionFlash = celebrate
        if (celebrate) {
            // Frame-clock delay respects system motion scaling and is testable without wall-clock sleeps.
            Animatable(0f).animateTo(1f, tween(1800, easing = LinearEasing))
            completionFlash = false
            changeExpanded(false)
        }
    }
    val accent by animateColorAsState(when {
        settled -> Teal300
        focus?.status == TodoStatus.Blocked -> Amber300
        else -> Purple400
    }, tween(420), label = "todoAccent")
    val quiet = settled && !completionFlash
    val progress by animateFloatAsState(if (tasks.isEmpty()) 0f else closed.toFloat() / tasks.size,
        tween(650, easing = FastOutSlowInEasing), label = "todoProgress")
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "todoArrow")
    val headerHeight = todoPanelHeaderHeight()
    val shape = RoundedCornerShape(13.dp)
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight).shadow(10.dp, shape).clip(shape)
        .background(Base1)
        .testTag("floatingTodoPanel")) {
        Box(Modifier.fillMaxWidth().height(headerHeight).testTag("todoHeader")
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                contentDescription = "Task plan, $closed of ${tasks.size} settled"
            }
            .clickable(role = Role.Button, onClick = rememberHapticOnClick { onExpandedChange(!expanded) })
            .padding(4.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.1f))
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                if (progress > 0f) Box(Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(
                        Purple400.copy(alpha = 0.28f), accent.copy(alpha = 0.32f), Teal300.copy(alpha = 0.28f)))))
                Row(Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(start = 28.dp, end = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TODO ($closed/${tasks.size})", style = MaterialTheme.typography.labelMedium,
                        color = if (quiet) TextMid else TextHigh)
                    if (focus?.status == TodoStatus.Active) Text(focus.content,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = TextHigh, style = MaterialTheme.typography.labelMedium)
                }
                if (focus?.status == TodoStatus.Active) GlowDot(accent,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp).todoDotBreathing(live),
                    size = 6.dp)
                else Icon(if (settled) Icons.Outlined.CheckCircle else Icons.Outlined.Checklist,
                    null, Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(16.dp), tint = accent)
                Icon(Icons.Outlined.ExpandMore, null,
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(16.dp).rotate(arrow), tint = TextMid)
            }
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(180)) + expandVertically(tween(300)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(260))) {
            Column(Modifier.fillMaxWidth().heightIn(max = (maxHeight - headerHeight).coerceAtLeast(1.dp))
                .testTag("todoScrollBody").verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.phases.forEachIndexed { phaseIndex, phase ->
                    key(phaseIndex, phase.name) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((phaseIndex + 1).toString().padStart(2, '0'), color = accent,
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
                            Text(phase.name, color = TextHigh, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            Text("${phase.tasks.count { it.status == TodoStatus.Completed || it.status == TodoStatus.Abandoned }}/${phase.tasks.size}",
                                color = TextMid, style = MaterialTheme.typography.labelSmall)
                        }
                        phase.tasks.forEachIndexed { index, task ->
                            key(index, task.content) { QuestTaskRow(task, live) }
                        }
                    }
                }
            }
        }
    }
}
