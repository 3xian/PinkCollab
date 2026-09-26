package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.ModelCatalog
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

internal data class ComposerRailAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val contentColor: Color,
    val fill: Brush? = null,
    val onClick: () -> Unit,
)

/**
 * Visible labels are not wire names. Stop sends interrupt and aborts the turn.
 * Exit only opens confirmation; the caller sends stop after that.
 */
internal fun composerRailActions(
    showStart: Boolean,
    canStart: Boolean,
    canInterrupt: Boolean,
    canExit: Boolean,
    canSend: Boolean,
    sendContentColor: Color,
    sendFill: Brush,
    onCommand: (SessionUserCommand) -> Unit,
    onExit: () -> Unit,
    onSend: () -> Unit,
): List<ComposerRailAction> = buildList {
    if (showStart) {
        add(ComposerRailAction(Icons.Outlined.PlayArrow, "Start", canStart, Purple200) {
            onCommand(SessionUserCommand.Start)
        })
    }
    add(ComposerRailAction(Icons.Outlined.Stop, "Stop", canInterrupt, TextMid) {
        onCommand(SessionUserCommand.Interrupt)
    })
    add(ComposerRailAction(Icons.AutoMirrored.Outlined.Logout, "Exit", canExit, TextMid, onClick = onExit))
    add(ComposerRailAction(Icons.AutoMirrored.Outlined.Send, "Send", canSend, sendContentColor, sendFill, onSend))
}

private val ComposerRailWidth = 88.dp

@Composable
internal fun ComposerRail(actions: List<ComposerRailAction>, modifier: Modifier = Modifier) {
    Column(Modifier.fillMaxHeight().width(ComposerRailWidth).then(modifier)) {
        actions.forEachIndexed { index, action ->
            if (index > 0 && action.fill == null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            }
            ComposerRailButton(
                icon = action.icon,
                label = action.label,
                onClick = action.onClick,
                enabled = action.enabled,
                contentColor = action.contentColor,
                modifier = Modifier.weight(1f),
                fill = action.fill ?: SolidColor(Color.Transparent),
            )
        }
    }
}

@Composable
private fun ComposerRailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
    fill: Brush = SolidColor(Color.Transparent),
) {
    val color = if (enabled) contentColor else Gray400.copy(alpha = 0.34f)
    Box(
        modifier
            .fillMaxWidth()
            .background(fill)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Visible composer copy is the selected model name, never the word "Model". */
internal fun composerModelLabel(model: ModelInfo?): String {
    model ?: return "Not selected"
    return model.name.takeIf { it.isNotBlank() } ?: model.id.takeIf { it.isNotBlank() } ?: "Not selected"
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
            .heightIn(min = 36.dp)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick))
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
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

internal fun isSelectedModel(current: ModelInfo?, candidate: ModelInfo): Boolean {
    current ?: return false
    return current.provider == candidate.provider && current.id == candidate.id
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    state: LoadState<ModelCatalog>?,
    current: ModelInfo?,
    enabled: Boolean,
    dismiss: () -> Unit,
    retry: () -> Unit,
    select: (ModelInfo) -> Unit,
    selectThinkingLevel: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                is LoadState.Ready -> if (state.value.models.isEmpty()) {
                    Text(
                        "No models are available from OMP.",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        color = TextMid,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        itemsIndexed(
                            state.value.models,
                            key = { _, model -> "${model.provider}/${model.id}" },
                        ) { _, model ->
                            val selected = isSelectedModel(current, model)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = enabled) { select(model) }
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${model.provider} · ${model.id}",
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
            if (state is LoadState.Ready && state.value.thinkingLevels.isNotEmpty()) {
                Text("Thinking level", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.value.thinkingLevels.forEach { level ->
                        FilterChip(
                            selected = current?.thinkingLevel == level,
                            onClick = { selectThinkingLevel(level) },
                            label = { Text(level) },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}
