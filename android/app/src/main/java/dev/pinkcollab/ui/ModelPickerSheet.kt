package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    state: LoadState<ModelCatalog>?,
    current: ModelInfo?,
    enabled: Boolean,
    runtimeAttached: Boolean,
    runtimeStarting: Boolean,
    canStartRuntime: Boolean,
    startRuntime: () -> Unit,
    dismiss: () -> Unit,
    retry: () -> Unit,
    refresh: () -> Unit,
    apply: (ModelSettingsChanges) -> Unit,
) {
    var pending by remember { mutableStateOf(ModelSettingsDraft.from(current)) }
    var query by remember { mutableStateOf("") }
    var expandedProviders by remember { mutableStateOf<Set<String>?>(null) }
    var searchCollapsedProviders by remember(query) { mutableStateOf(emptySet<String>()) }
    val changes = modelSettingsChanges(current, pending)
    val ready = state as? LoadState.Ready
    val canEdit = enabled && ready != null && !ready.refreshing
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                ),
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxHeight(0.93f)
                    .semantics { paneTitle = "Model settings" },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    ModelPickerHeader(dismiss, refresh, runtimeAttached &&
                        ((ready != null && !ready.refreshing) || state is LoadState.Failed))
                    if (runtimeAttached && ready != null) {
                        val catalog = ready.value
                        if (catalog.models.isNotEmpty()) ModelSearchField(query) { query = it }
                        val thinkingLevels = thinkingLevelsForModel(catalog, pending.model, current)
                        if (thinkingLevels.isNotEmpty()) {
                            ThinkingLevelSlider(thinkingLevels, pending.thinkingLevel, canEdit) {
                                pending = pending.copy(thinkingLevel = it)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val grouped = groupModelsByProvider(filterModels(catalog.models, query))
                        if (catalog.models.isEmpty() || grouped.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text(if (catalog.models.isEmpty()) "No models are available from OMP."
                                    else "No models match \"$query\"", color = TextMid)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("modelList")) {
                                grouped.forEach { (provider, models) ->
                                    val expanded = if (query.isNotBlank()) provider !in searchCollapsedProviders
                                        else provider in (expandedProviders ?: initiallyExpandedProviders(catalog, current))
                                    item(key = "provider:$provider") {
                                        ModelProviderHeader(provider, models.size, expanded) {
                                            if (query.isNotBlank()) {
                                                searchCollapsedProviders = if (expanded) searchCollapsedProviders + provider
                                                    else searchCollapsedProviders - provider
                                            } else {
                                                val old = expandedProviders ?: initiallyExpandedProviders(catalog, current)
                                                expandedProviders = if (expanded) old - provider else old + provider
                                            }
                                        }
                                    }
                                    if (expanded) {
                                        items(models, key = { "model:${it.provider}/${it.id}" }) { model ->
                                            CompactModelRow(model, isSelectedModel(pending.model, model), canEdit) {
                                                pending = selectModelDraft(catalog, current, pending, model)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            when {
                                !runtimeAttached -> Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(24.dp),
                                ) {
                                    Text(
                                        if (runtimeStarting) "Starting OMP…" else "Start OMP to choose a model before sending your next message.",
                                        color = TextMid,
                                    )
                                    if (runtimeStarting) CircularProgressIndicator(color = Purple400)
                                    else Button(onClick = rememberHapticOnClick(startRuntime), enabled = canStartRuntime) { Text("Start runtime") }
                                }
                                state == null || state == LoadState.Loading -> CircularProgressIndicator(color = Purple400)
                                state is LoadState.Failed -> Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(24.dp),
                                ) {
                                    Text(state.message, color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(onClick = rememberHapticOnClick(retry)) { Text("Retry") }
                                }
                                else -> Text("No models are available from OMP.", color = TextMid)
                            }
                        }
                    }
                    ModelPickerActions(
                        dismiss = dismiss,
                        apply = { apply(changes) },
                        applyEnabled = canEdit && !changes.isEmpty && runtimeAttached,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerHeader(dismiss: () -> Unit, refresh: () -> Unit, canRefresh: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Models", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = rememberHapticOnClick(refresh), enabled = canRefresh, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh models")
        }
        IconButton(onClick = rememberHapticOnClick(dismiss), modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Close model settings")
        }
    }
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "Search models" },
        placeholder = { Text("Search models…") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ModelProviderHeader(provider: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = rememberHapticOnClick(onToggle))
                .semantics {
                    contentDescription = "$provider models"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .testTag("provider:$provider")
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(Purple400))
            Spacer(Modifier.width(12.dp))
            Text(provider.uppercase(), modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                color = TextHigh, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(count.toString(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = TextMid)
            }
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null, modifier = Modifier.size(20.dp), tint = Purple200)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CompactModelRow(model: ModelInfo, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 26.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .heightIn(min = 50.dp)
            .background(if (selected) Purple700.copy(alpha = 0.7f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onSelect))
            .semantics { this.selected = selected; contentDescription = "${model.name}, ${model.provider}" }
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape)
            .background(if (selected) Purple400 else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(12.dp))
        Text(model.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TextHigh else MaterialTheme.colorScheme.onSurface)
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = Purple400)
        }
    }
}

@Composable
private fun ModelPickerActions(dismiss: () -> Unit, apply: () -> Unit, applyEnabled: Boolean) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = rememberHapticOnClick(dismiss), modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        Button(onClick = rememberHapticOnClick(apply), enabled = applyEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Apply")
        }
    }
}
