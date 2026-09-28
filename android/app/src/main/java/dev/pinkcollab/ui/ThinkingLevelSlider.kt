package dev.pinkcollab.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pinkcollab.ui.theme.TextMid
import kotlin.math.exp
import kotlin.math.roundToInt

/** A continuous drag follows the finger with resistance at the ends, then springs to a model-provided level. */
@Composable
internal fun ThinkingLevelSlider(
    levels: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (levels.isEmpty()) return
    val latestOnSelect by rememberUpdatedState(onSelect)
    var settledIndex by remember(levels) { mutableStateOf(levels.indexOf(selected).takeIf { it >= 0 }) }
    var dragging by remember(levels) { mutableStateOf(false) }
    var dragX by remember(levels) { mutableFloatStateOf(0f) }
    LaunchedEffect(levels, selected) {
        if (!dragging) settledIndex = levels.indexOf(selected).takeIf { it >= 0 }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Thinking", style = MaterialTheme.typography.labelLarge, color = TextMid,
            modifier = Modifier.padding(bottom = 8.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(48.dp)
                .testTag("thinkingSlider")
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val cellWidth = maxWidth / levels.size
            val cellPx = constraints.maxWidth.toFloat() / levels.size
            val maxX = (levels.size - 1) * cellPx
            val position = if (dragging) dampedDragPosition(dragX, maxX, cellPx * 0.35f)
                else (settledIndex ?: 0) * cellPx
            val animatedX by animateFloatAsState(
                targetValue = position,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                label = "Thinking level position",
            )
            val visualIndex = if (dragging) ((dragX + cellPx / 2) / cellPx).roundToInt()
                .coerceIn(levels.indices) else settledIndex

            if (dragging || settledIndex != null) {
                Box(
                    Modifier.offset { IntOffset(animatedX.roundToInt(), 0) }
                        .width(cellWidth).fillMaxHeight().padding(3.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            }
            Row(
                Modifier.fillMaxWidth().fillMaxHeight().pointerInput(levels, enabled, cellPx) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { start: Offset ->
                            dragging = true
                            dragX = start.x - cellPx / 2
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            dragX += delta
                        },
                        onDragEnd = {
                            val index = ((dragX + cellPx / 2) / cellPx).roundToInt()
                                .coerceIn(levels.indices)
                            settledIndex = index
                            dragging = false
                            latestOnSelect(levels[index])
                        },
                        onDragCancel = { dragging = false },
                    )
                },
            ) {
                levels.forEachIndexed { index, level ->
                    val isSelected = visualIndex == index
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(enabled = enabled, role = Role.RadioButton) {
                                settledIndex = index
                                latestOnSelect(level)
                            }
                            .semantics { this.selected = isSelected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(level, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun dampedDragPosition(raw: Float, max: Float, resistance: Float): Float = when {
    raw < 0f -> -resistance * (1f - exp(raw / resistance))
    raw > max -> max + resistance * (1f - exp((max - raw) / resistance))
    else -> raw
}
