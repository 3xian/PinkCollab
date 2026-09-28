package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        title = { Text("Exit this runtime?") },
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
            ) { Text("Exit", fontWeight = FontWeight.SemiBold) }
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

private val ComposerRailMinWidth = 64.dp

/** Each rail action keeps a 48 dp touch target even when the composer card is short. */
private val ComposerRailActionMinHeight = 48.dp

/** Stop aborts the turn; Exit opens confirmation before stopping the runtime. */
@Composable
internal fun ComposerRail(
    controls: SessionControlsState,
    onCommand: (SessionUserCommand) -> Unit,
    onExit: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        Modifier
            .fillMaxHeight()
            // Expand with scaled labels rather than clipping them inside a fixed-width rail.
            .widthIn(min = ComposerRailMinWidth)
            .width(IntrinsicSize.Max)
            .heightIn(min = ComposerRailActionMinHeight * 3)
            .then(modifier),
    ) {
        ComposerRailButton(
            label = "Stop",
            onClick = { onCommand(SessionUserCommand.Interrupt) },
            enabled = controls.canInterrupt,
            contentColor = TextMid,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        ComposerRailButton(
            label = "Exit",
            onClick = onExit,
            enabled = controls.canStop,
            contentColor = TextMid,
            modifier = Modifier.weight(1f),
        )
        ComposerRailButton(
            label = "Send",
            onClick = onSend,
            enabled = controls.canSend,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.weight(1f),
            // Disabled Send is a plain rail row like Stop: no fill, same dim label.
            fill = if (controls.canSend) BrandGradient else null,
        )
    }
}

@Composable
private fun ComposerRailButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fill: Brush? = null,
) {
    val color = if (enabled) contentColor else Gray400.copy(alpha = 0.34f)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (fill == null) Modifier else Modifier.background(fill))
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick)),
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

/** Visible composer copy is the selected model name, never the word "Model". */
internal fun composerModelLabel(model: ModelInfo?): String {
    model ?: return "Select model"
    return model.name.takeIf { it.isNotBlank() } ?: model.id.takeIf { it.isNotBlank() } ?: "Select model"
}

internal fun composerThinkingLabel(model: ModelInfo?): String? =
    model?.thinkingLevel?.takeIf { it.isNotBlank() }

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
    val thinkingColor = if (enabled) TextMid else Gray400.copy(alpha = 0.34f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = ComposerContentInset)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        if (thinkingLevel != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                thinkingLevel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = thinkingColor,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
    }
}
