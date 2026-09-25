package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.ui.theme.*

@Composable
internal fun StopConfirmationDialog(
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Outlined.StopCircle, null, tint = TextMid) },
        title = { Text("Stop this session?") },
        text = {
            Text(
                "This ends the current OMP process. The conversation will remain visible, but the action cannot be undone.",
                color = TextMid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = rememberHapticOnClick(confirm),
                colors = ButtonDefaults.textButtonColors(contentColor = TextHigh),
            ) { Text("Stop session", fontWeight = FontWeight.SemiBold) }
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

@Composable
internal fun ComposerActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color,
) {
    TextButton(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 5.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = Gray400.copy(alpha = 0.34f),
        ),
    ) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun ComposerSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    // Keep the visual pill compact without shrinking or overlapping its 48dp touch target.
    val shape = RoundedCornerShape(percent = 50)
    val fill = if (enabled) BrandGradient else SolidColor(Gray400.copy(alpha = 0.16f))
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else Gray400.copy(alpha = 0.58f)
    Surface(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = shape,
        color = Color.Transparent,
        contentColor = contentColor,
    ) {
        Box(
            Modifier
                .padding(vertical = 9.dp)
                .height(30.dp)
                .background(fill, shape)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Send",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
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
