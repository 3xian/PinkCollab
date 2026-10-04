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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.*

internal val TodoInk = RetroText
internal val TodoMutedInk = RetroMutedText
internal val TodoActiveInk = Color(0xFFE4BD7C)
internal val TodoCompletedInk = Color(0xFFA8BB79)
internal val TodoBlockedInk = Color(0xFFE39B86)

internal val TodoPanelCollapsedHeight = 72.dp

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
        settled -> TodoCompletedInk
        focus?.status == TodoStatus.Blocked -> TodoBlockedInk
        else -> TodoActiveInk
    }, tween(420), label = "todoAccent")
    val quiet = settled && !completionFlash
    val progress by animateFloatAsState(if (tasks.isEmpty()) 0f else closed.toFloat() / tasks.size,
        tween(650, easing = FastOutSlowInEasing), label = "todoProgress")
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "todoArrow")
    val headerHeight = todoPanelHeaderHeight()
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("floatingTodoPanel"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().height(headerHeight)
            .retroPanel(accented = !quiet)
            .testTag("todoHeader")
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                contentDescription = "Task plan, $closed of ${tasks.size} settled"
            }
            .clickable(role = Role.Button, onClick = rememberHapticOnClick { onExpandedChange(!expanded) })
            .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (settled) Icons.Outlined.CheckCircle else Icons.Outlined.Checklist,
                    null, Modifier.size(18.dp), tint = RetroBrass)
                Text("TASK PLAN", Modifier.weight(1f), color = RetroBrass,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Default)
                Text("$closed/${tasks.size}", color = RetroText,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp).rotate(arrow), tint = RetroText)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (focus?.status == TodoStatus.Active) GlowDot(accent,
                    modifier = Modifier.todoDotBreathing(live), size = 5.dp)
                Text(if (settled) "Plan settled" else focus?.content ?: "Task plan",
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = if (quiet) TodoMutedInk else TodoInk)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(1.dp))
                .background(RetroInk)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                if (progress > 0f) Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
            }
        }
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(420, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(320, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top)) {
            Box(Modifier.fillMaxWidth(0.96f)
                .heightIn(max = (maxHeight - headerHeight).coerceAtLeast(1.dp))
                .retroPanel(inset = true)) {
                Column(Modifier.fillMaxWidth().testTag("todoScrollBody")
                    .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.phases.forEachIndexed { phaseIndex, phase ->
                        key(phaseIndex, phase.name) {
                            Row(Modifier.fillMaxWidth().retroPanel()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text((phaseIndex + 1).toString().padStart(2, '0'), color = accent,
                                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
                                Text(phase.name, color = TodoInk, style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("${phase.tasks.count { it.status == TodoStatus.Completed || it.status == TodoStatus.Abandoned }}/${phase.tasks.size}",
                                    color = TodoMutedInk, style = MaterialTheme.typography.labelSmall)
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
}
