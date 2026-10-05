package dev.pinkcollab.ui

import dev.pinkcollab.data.TodoStatus
import dev.pinkcollab.data.TodoTask

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/** Animate drawing only; retained plans have no live motion or changing layout bounds. */
@Composable
internal fun Modifier.todoDotBreathing(live: Boolean): Modifier {
    if (!live) return this
    val transition = rememberInfiniteTransition(label = "todoDotBreathing")
    val breath = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "todoDotBreath",
    )
    return graphicsLayer {
        alpha = 0.45f + 0.55f * breath.value
        scaleX = 0.8f + 0.3f * breath.value
        scaleY = scaleX
    }
}

@Composable
internal fun QuestTaskRow(task: TodoTask, live: Boolean) {
    val target = when (task.status) {
        TodoStatus.Active -> Purple400
        TodoStatus.Completed -> Teal300
        TodoStatus.Blocked -> Color(0xFFE8B96C)
        else -> TextMid
    }
    val color by animateColorAsState(target, tween(350), label = "taskColor")
    val textColor by animateColorAsState(when (task.status) {
        TodoStatus.Active -> Purple400
        TodoStatus.Abandoned -> TextMid
        else -> TextHigh
    }, tween(350), label = "taskTextColor")
    val textPulse = if (task.status == TodoStatus.Active && live) {
        val transition = rememberInfiniteTransition(label = "runningTaskText")
        val pulse by transition.animateFloat(0.05f, 0.35f,
            infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "taskTextPulse")
        pulse
    } else 0f
    val burst = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(task.status) }
    LaunchedEffect(task.status, live) {
        val celebrate = live && previous != TodoStatus.Completed && task.status == TodoStatus.Completed
        previous = task.status
        if (celebrate) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(700, easing = LinearOutSlowInEasing))
        }
    }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        stateDescription = task.status.label
    }.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (task.status == TodoStatus.Active) GlowDot(color,
                modifier = Modifier.todoDotBreathing(live), size = 8.dp)
            else {
                val icon = when (task.status) {
                    TodoStatus.Completed -> Icons.Outlined.CheckCircle
                    TodoStatus.Blocked -> Icons.Outlined.Lock
                    TodoStatus.Abandoned -> Icons.Outlined.RemoveCircleOutline
                    else -> Icons.Outlined.RadioButtonUnchecked
                }
                Icon(icon, null, Modifier.size(19.dp).graphicsLayer {
                    val pop = sin(burst.value * Math.PI).toFloat() * 0.25f
                    scaleX = 1f + pop; scaleY = 1f + pop
                }, tint = color)
            }
            if (burst.value < 1f) Canvas(Modifier.size(28.dp)) {
                repeat(8) { i ->
                    val angle = i * Math.PI / 4
                    val radius = size.minDimension * (0.4f + burst.value * 0.65f)
                    drawCircle(color.copy(alpha = 1f - burst.value), 1.5.dp.toPx(),
                        center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius))
                }
            }
        }
        Text(task.content + if (task.blocker.isNotBlank()) " · ${task.blocker}" else "",
            modifier = Modifier.weight(1f),
            color = lerp(textColor, TextHigh, textPulse),
            style = MaterialTheme.typography.bodySmall,
            textDecoration = if (task.status == TodoStatus.Abandoned) TextDecoration.LineThrough else null)
    }
}
