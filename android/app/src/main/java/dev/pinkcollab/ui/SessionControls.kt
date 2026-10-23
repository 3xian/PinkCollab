package dev.pinkcollab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.ui.theme.*

@Composable
internal fun ExitConfirmationDialog(
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = TextMid) },
        title = { Text("End this runtime?", style = DialogHeadlineStyle) },
        text = {
            Text(
                "This ends the current OMP process. The conversation stays, but this cannot be undone.",
                color = TextMid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = rememberHapticOnClick(confirm),
                colors = ButtonDefaults.textButtonColors(contentColor = TextHigh),
            ) { Text("End", fontWeight = FontWeight.SemiBold) }
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

/** Leading/trailing inset for composer content; the composer card itself stays unpadded. */
internal val ComposerContentInset = 14.dp

private val ComposerRailMinWidth = 52.dp

/** Compact rail surfaces; Compose expands clickable touch bounds to the minimum target. */
private val ComposerRailActionMinHeight = 44.dp


/** Stop aborts the turn; End opens confirmation before stopping the runtime. */
@Composable
internal fun ComposerRail(
    controls: SessionControlsState,
    onCommand: (SessionUserCommand) -> Unit,
    onExit: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            // Expand with scaled labels rather than clipping them inside a fixed-width rail.
            .widthIn(min = ComposerRailMinWidth)
            .width(IntrinsicSize.Max)
            .wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ComposerRailButton(
            label = "End",
            onClick = onExit,
            enabled = controls.canStop,
            contentColor = TextMid,
        )
        ComposerRailButton(
            label = "Stop",
            onClick = { onCommand(SessionUserCommand.Interrupt) },
            enabled = controls.canInterrupt,
            contentColor = TextMid,
        )
        ComposerRailButton(
            label = "Send",
            onClick = onSend,
            enabled = controls.canSend,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            fill = SolidColor(if (controls.canSend) BrandPurple else Purple400.copy(alpha = 0.12f)),
        )
    }
}

@Composable
private fun ComposerRailButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    contentColor: Color,
    fill: Brush? = null,
) {
    val color = if (enabled) contentColor else Gray400.copy(alpha = 0.34f)
    val shape = RoundedCornerShape(12.dp)
    val background = fill ?: Brush.verticalGradient(
        listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainer),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ComposerRailActionMinHeight)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/** Detached conversations use OMP's current default when their runtime starts. */
internal fun composerModelLabel(model: ModelInfo?): String {
    model ?: return "model"
    return model.name.takeIf { it.isNotBlank() } ?: model.id.takeIf { it.isNotBlank() } ?: "model"
}

internal fun composerThinkingLabel(model: ModelInfo?): String? =
    model?.thinkingLevel?.takeIf { it.isNotBlank() }

@Composable
internal fun ComposerSettingsBar(
    model: ModelInfo?,
    canChooseModel: Boolean,
    onChooseModel: () -> Unit,
    onFastChange: (Boolean) -> Unit,
    onQuickInputs: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ComposerModelButton(
                label = composerModelLabel(model),
                thinkingLevel = composerThinkingLabel(model),
                onClick = onChooseModel,
                enabled = canChooseModel,
                modifier = Modifier.weight(1f, fill = false),
            )
            ComposerFastButton(
                checked = model?.fastModeEnabled == true,
                active = model?.fastModeActive == true,
                enabled = canChooseModel,
                onCheckedChange = onFastChange,
            )
        }
        IconButton(onClick = rememberHapticOnClick(onQuickInputs), modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.TextSnippet, contentDescription = "Quick inputs", tint = TextMid,
                modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ComposerFastButton(
    checked: Boolean,
    enabled: Boolean,
    active: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val toggle = rememberHapticOnClick { onCheckedChange(!checked) }
    val color = (if (checked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        .copy(alpha = if (enabled) 1f else 0.34f)
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .toggleable(value = checked, interactionSource = remember { MutableInteractionSource() },
                indication = null, enabled = enabled, role = Role.Button,
                onValueChange = { toggle() })
            .semantics {
                contentDescription = if (checked && !active) "Fast mode, currently inactive" else "Fast mode"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Fast",
            modifier = Modifier
                .drawWithCache {
                    val width = 1.dp.toPx()
                    val stroke = Stroke(
                        width = width,
                        pathEffect = if (checked) null else PathEffect.dashPathEffect(
                            floatArrayOf(3.dp.toPx(), 2.dp.toPx()),
                        ),
                    )
                    onDrawBehind {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(width / 2, width / 2),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius(size.height / 2),
                            style = stroke,
                        )
                    }
                }
                .padding(horizontal = 10.dp, vertical = 3.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ComposerModelButton(
    label: String,
    thinkingLevel: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = if (thinkingLevel == null) "Choose model: $label" else "Choose model: $label, thinking $thinkingLevel"
    val color = if (enabled) Purple200 else Gray400.copy(alpha = 0.34f)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = ComposerContentInset)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        if (thinkingLevel != null) {
            Spacer(Modifier.width(8.dp))
            ComposerThinkingIndicator(thinkingLevel, color)
        }
    }
}

/** Static effort gauge, not elapsed work or an indeterminate loading spinner. */
@Composable
private fun ComposerThinkingIndicator(level: String, color: Color) {
    val fraction = when (level.trim().lowercase()) {
        "off" -> 0f
        "minimal" -> 0.125f
        "low" -> 0.25f
        "med", "medium" -> 0.5f
        "high" -> 0.75f
        "xhigh", "max" -> 1f
        else -> null
    }
    val modifier = Modifier.size(14.dp).testTag("composerThinkingIndicator")
    if (fraction == null) {
        Icon(
            if (level.trim().equals("auto", ignoreCase = true)) Icons.Outlined.Autorenew else Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            modifier = modifier,
            tint = color,
        )
        return
    }
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val radius = (size.minDimension - stroke) / 2f
        drawCircle(color.copy(alpha = color.alpha * 0.22f), radius, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(stroke),
            )
        }
        if (level.trim().equals("max", ignoreCase = true)) {
            drawCircle(color, radius = 1.5.dp.toPx())
        }
    }
}
