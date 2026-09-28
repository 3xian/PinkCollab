package dev.pinkcollab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.TextMid
import dev.pinkcollab.ui.theme.Teal300
import dev.pinkcollab.ui.theme.BrandPurple
import dev.pinkcollab.ui.theme.BrandPink
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingLevelSlider(
    levels: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (levels.isEmpty()) return
    val selectedIndex = levels.indexOf(selected)
    val selectedLabel = levels.getOrNull(selectedIndex) ?: "Not set"
    val sliderEnabled = enabled && levels.size > 1
    val accent = Teal300

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Thinking", style = MaterialTheme.typography.labelLarge, color = TextMid)
            Text(selectedLabel, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        if (selectedIndex < 0 || levels.size == 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                levels.forEach { level ->
                    FilterChip(
                        selected = selected == level,
                        onClick = { onSelect(level) },
                        enabled = enabled,
                        label = { Text(level) },
                        modifier = Modifier.testTag(
                            if (levels.size == 1) "thinkingSingleLevel" else "thinkingLevel:$level",
                        ),
                    )
                }
            }
            return@Column
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { value -> onSelect(levels[value.roundToInt().coerceIn(levels.indices)]) },
            enabled = sliderEnabled,
            track = { state ->
                val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
                val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val radius = size.height / 2
                    val startX = radius
                    val endX = (size.width - radius).coerceAtLeast(startX)
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val start = Offset(if (rtl) endX else startX, radius)
                    val end = Offset(if (rtl) startX else endX, radius)
                    val range = state.valueRange
                    val fraction = ((state.value - range.start) / (range.endInclusive - range.start))
                        .coerceIn(0f, 1f)
                    drawLine(inactiveColor, start, end, size.height, StrokeCap.Round)
                    if (fraction > 0f) {
                        val activeEnd = Offset(start.x + (end.x - start.x) * fraction, radius)
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = if (sliderEnabled) listOf(BrandPurple, BrandPink)
                                    else listOf(disabledColor, disabledColor),
                                start = start,
                                end = activeEnd,
                            ),
                            start = start,
                            end = activeEnd,
                            strokeWidth = size.height,
                            cap = StrokeCap.Round,
                        )
                    }
                    if (levels.size > 1) {
                        levels.indices.forEach { index ->
                            val tickFraction = index.toFloat() / levels.lastIndex
                            drawCircle(
                                color = tickColor.copy(alpha = 0.6f),
                                radius = 1.dp.toPx(),
                                center = Offset(start.x + (end.x - start.x) * tickFraction, radius),
                            )
                        }
                    }
                }
            },
            thumb = {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (sliderEnabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            },
            valueRange = 0f..levels.lastIndex.coerceAtLeast(1).toFloat(),
            steps = (levels.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag("thinkingSlider").semantics {
                contentDescription = "Thinking"
                stateDescription = selectedLabel
            },
        )
    }
}
