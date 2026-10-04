package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DataUsage
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

private enum class ModelPickerAvailability(val message: String? = null) {
    Available,
    RuntimeDetached("Connecting to OMP…"),
    RuntimeStarting("Starting runtime…"),
    LoadFailed("Models could not be loaded. Retry to continue."),
    Loading("Loading models…"),
    Refreshing("Refreshing models…"),
    Busy("Model settings are temporarily unavailable."),
}

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
    usageState: LoadState<dev.pinkcollab.data.UsageSnapshot>? = null,
    loadUsage: () -> Unit = {},
    runtimeStartAttempt: RuntimeStartAttempt? = null,
) {
    LaunchedEffect(Unit) {
        if (!runtimeAttached && !runtimeStarting && runtimeStartAttempt !is RuntimeStartAttempt.Pending && canStartRuntime)
            startRuntime()
    }
    var showUsage by remember { mutableStateOf(false) }
    if (showUsage) UsageSheet(usageState, loadUsage) { showUsage = false }
    var pending by remember { mutableStateOf(ModelSettingsDraft.from(current)) }
    LaunchedEffect(current) {
        if (pending.model == null && current != null) pending = ModelSettingsDraft.from(current)
    }
    var query by remember { mutableStateOf("") }
    var expandedProviders by remember { mutableStateOf<Set<String>?>(null) }
    var searchCollapsedProviders by remember(query) { mutableStateOf(emptySet<String>()) }
    val changes = modelSettingsChanges(current, pending)
    val ready = state as? LoadState.Ready
    val availability = when {
        !runtimeAttached -> if (runtimeStarting) ModelPickerAvailability.RuntimeStarting
            else ModelPickerAvailability.RuntimeDetached
        state is LoadState.Failed -> ModelPickerAvailability.LoadFailed
        ready == null -> ModelPickerAvailability.Loading
        ready.refreshing -> ModelPickerAvailability.Refreshing
        !enabled -> ModelPickerAvailability.Busy
        else -> ModelPickerAvailability.Available
    }
    val canEdit = availability == ModelPickerAvailability.Available
    if (!showUsage) Dialog(
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
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .then(if (runtimeAttached) Modifier.fillMaxHeight(0.93f) else Modifier)
                    .retroPanel()
                    .semantics { paneTitle = "Model settings" },
                shape = RoundedCornerShape(3.dp),
                color = Color.Transparent,
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    ModelPickerHeader({ showUsage = true; loadUsage() }, refresh, runtimeAttached, runtimeAttached &&
                        ((ready != null && !ready.refreshing) || state is LoadState.Failed))
                    if (!runtimeAttached) {
                        DefaultModelContent(
                            runtimeStartAttempt !is RuntimeStartAttempt.Failed &&
                                (runtimeStarting || runtimeStartAttempt is RuntimeStartAttempt.Pending),
                            canStartRuntime, startRuntime, dismiss,
                            (runtimeStartAttempt as? RuntimeStartAttempt.Failed)?.message)
                    } else if (ready != null) {
                        val catalog = ready.value
                        if (catalog.models.isNotEmpty()) ModelSearchField(query) { query = it }
                        else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                                            CompactModelRow(model, isSelectedModel(pending.model, model),
                                                isSelectedModel(current, model), canEdit) {
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
                                state == null || state == LoadState.Loading -> CircularProgressIndicator(color = RetroBrass)
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
                    if (runtimeAttached && ready != null) {
                        val thinkingLevels = thinkingLevelsForModel(ready.value, pending.model, current)
                        if (thinkingLevels.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ThinkingLevelSlider(thinkingLevels, pending.thinkingLevel, canEdit) {
                                pending = pending.copy(thinkingLevel = it)
                            }
                        }
                    }
                    if (runtimeAttached) ModelPickerActions(
                        dismiss = dismiss,
                        apply = { apply(changes) },
                        unchanged = changes.isEmpty,
                        availability = availability,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelPickerHeader(usage: () -> Unit, refresh: () -> Unit, showRefresh: Boolean, canRefresh: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Models", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showRefresh) TextButton(onClick = rememberHapticOnClick(refresh), enabled = canRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reload")
            }
            TextButton(onClick = rememberHapticOnClick(usage)) {
                Icon(Icons.Outlined.DataUsage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Usage")
            }
        }
    }
}

@Composable
private fun DefaultModelContent(starting: Boolean, canStart: Boolean, start: () -> Unit, dismiss: () -> Unit, error: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (starting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = RetroBrass, strokeWidth = 2.dp)
                Text("Starting OMP…", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            }
        } else if (error != null) {
            Text("Could not start OMP\n$error", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        } else {
            Text("Waiting to connect to OMP.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
        }
        if (!starting) Button(
            onClick = rememberHapticOnClick(start), enabled = canStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Retry") }
        TextButton(onClick = rememberHapticOnClick(dismiss), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Close")
        }
    }
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .retroPanel(inset = true).semantics { contentDescription = "Search models" },
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Search models…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        shape = RectangleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = RetroBrass,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            disabledIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = RetroBrass,
            focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        singleLine = true,
    )
}

@Composable
private fun ModelProviderHeader(provider: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .retroPanel()
                .clickable(role = Role.Button, onClick = rememberHapticOnClick(onToggle))
                .semantics {
                    contentDescription = "$provider models"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .testTag("provider:$provider")
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(provider.uppercase(), modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                color = TextHigh, fontWeight = FontWeight.SemiBold)
            Surface(modifier = Modifier.retroPanel(inset = true), shape = RoundedCornerShape(3.dp), color = Color.Transparent) {
                Text(count.toString(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = TextMid)
            }
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null, modifier = Modifier.size(20.dp), tint = RetroBrass)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CompactModelRow(model: ModelInfo, selected: Boolean, current: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    TextButton(
        onClick = rememberHapticOnClick(onSelect),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .heightIn(min = SmallButtonHeight)
            .then(if (selected) Modifier.retroPanel(accented = true) else Modifier)
            .semantics {
                this.selected = selected
                contentDescription = "${model.name}, ${model.provider}"
                if (current) stateDescription = "Current model"
            },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = RetroText,
            disabledContainerColor = Color.Transparent,
        ),
    ) {
        val markerColor = if (!enabled) LocalContentColor.current
            else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Box(Modifier.size(5.dp).clip(CircleShape).background(markerColor))
        Spacer(Modifier.width(8.dp))
        Text(model.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
        if (current) {
            Spacer(Modifier.width(8.dp))
            Text("Current", style = MaterialTheme.typography.labelSmall)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = markerColor)
        }
    }
}

@Composable
private fun ModelPickerActions(dismiss: () -> Unit, apply: () -> Unit, unchanged: Boolean, availability: ModelPickerAvailability) {
    val available = availability == ModelPickerAvailability.Available
    val done = unchanged && available
    Column {
        availability.message?.let { message ->
            Text(message, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = rememberHapticOnClick(dismiss),
                modifier = Modifier.height(SmallButtonHeight),
                contentPadding = SmallButtonPadding,
            ) { Text("Cancel") }
            PrimaryButton(
                onClick = if (done) dismiss else apply,
                enabled = available,
                modifier = Modifier.height(SmallButtonHeight),
                contentPadding = SmallButtonPadding,
            ) {
                Text(if (done) "Done" else "Apply")
            }
        }
    }
}
