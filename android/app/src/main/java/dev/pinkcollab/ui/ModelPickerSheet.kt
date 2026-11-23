package dev.pinkcollab.ui

import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import java.util.UUID

private enum class ModelPickerAvailability(val message: String? = null) {
    Available,
    Applying("Applying model settings…"),
    LoadFailed("Models could not be loaded. Retry to continue."),
    Loading("Loading models…"),
    Refreshing("Refreshing models…"),
    Busy("Model settings are temporarily unavailable."),
}

// Save only the user's choice. Capabilities and the descriptor sent to Gateway come from the current catalog.
private val EditedDraftSaver = Saver<ModelSettingsDraft?, Array<String?>>(
    save = { it?.let { draft -> arrayOf(draft.model?.provider, draft.model?.id, draft.model?.name, draft.thinkingLevel) } },
    restore = { (provider, id, name, level) ->
        ModelSettingsDraft(if (provider != null && id != null && name != null) ModelInfo(provider, id, name) else null, level)
    },
)

/** A restored sheet may await its original receipt, never start a new request from saved state. */
private class ModelPickerAttempts : ViewModel() {
    private val pending = mutableMapOf<String, Deferred<Unit>>()

    fun start(action: suspend () -> Unit): String {
        val id = UUID.randomUUID().toString()
        pending[id] = viewModelScope.async { action() }
        return id
    }

    operator fun get(id: String) = pending[id]
    fun release(id: String) { pending.remove(id)?.cancel() }
}

private const val InterruptedApply = "The previous wait was interrupted. Check the current settings before retrying."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    state: LoadState<ModelCatalog>?,
    current: ModelInfo?,
    enabled: Boolean,
    dismiss: () -> Unit,
    refresh: () -> Unit,
    apply: suspend (ModelSettingsChanges) -> Unit,
    usageState: LoadState<dev.pinkcollab.data.UsageSnapshot>? = null,
    loadUsage: () -> Unit = {},
) {
    var showUsage by rememberSaveable { mutableStateOf(false) }
    if (showUsage) UsageSheet(usageState, loadUsage) { showUsage = false }
    val attempts = viewModel { ModelPickerAttempts() }
    var attemptId by rememberSaveable { mutableStateOf<String?>(null) }
    var applyError by rememberSaveable { mutableStateOf<String?>(null) }
    val applying = attemptId != null
    val currentDismiss by rememberUpdatedState(dismiss)
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(attempts, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) attemptId?.let(attempts::release)
        }
    }
    LaunchedEffect(attemptId, lifecycle) {
        val id = attemptId ?: return@LaunchedEffect
        val receipt = attempts[id]
        val error = if (receipt == null) InterruptedApply else try {
            receipt.await()
            null
        } catch (e: CancellationException) {
            ensureActive() // Observer disposal must not settle or cancel the retained request.
            InterruptedApply
        } catch (e: Exception) {
            e.localizedMessage ?: "Could not apply model settings."
        }
        // Do not consume the result in a stopped activity whose saved state still contains this attempt ID.
        lifecycle.withResumed {
            if (attemptId == id) {
                attempts.release(id)
                attemptId = null
                applyError = error
                if (error == null) currentDismiss()
            }
        }
    }
    val close = {
        attemptId?.let(attempts::release)
        attemptId = null
        dismiss()
    }
    var edited by rememberSaveable(stateSaver = EditedDraftSaver) { mutableStateOf<ModelSettingsDraft?>(null) }
    val pending = edited ?: ModelSettingsDraft.from(current)
    var query by rememberSaveable { mutableStateOf("") }
    var expandedProviders by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var searchCollapsedProviders by rememberSaveable(query) { mutableStateOf(emptyList<String>()) }
    val ready = state as? LoadState.Ready
    val catalog = ready?.value
    val resolution = remember(catalog, current, pending) { catalog?.let { resolveModelDraft(it, current, pending) } }
    val availability = when {
        applying -> ModelPickerAvailability.Applying
        !enabled -> ModelPickerAvailability.Busy
        state is LoadState.Failed -> ModelPickerAvailability.LoadFailed
        ready == null -> ModelPickerAvailability.Loading
        ready.refreshing -> ModelPickerAvailability.Refreshing
        else -> ModelPickerAvailability.Available
    }
    val canEdit = availability == ModelPickerAvailability.Available
    if (!showUsage) Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .fillMaxHeight(0.93f)
                    .semantics { paneTitle = "Model settings" },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    val focus = LocalFocusManager.current
                    ModelPickerHeader({ showUsage = true; loadUsage() }, refresh,
                        canUseHost = enabled && !applying,
                        canRefresh = (ready != null && !ready.refreshing) || state is LoadState.Failed)
                    if (catalog != null) {
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
                                                edited = selectModelDraft(catalog, current, pending, model)
                                                applyError = null
                                                focus.clearFocus()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            when {
                                state == null || state == LoadState.Loading -> MorphIndicator(color = Purple400)
                                state is LoadState.Failed -> Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(24.dp),
                                ) {
                                    Text(state.message, color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(onClick = rememberHapticOnClick(refresh), enabled = enabled) { Text("Retry") }
                                }
                                else -> Text("No models are available from OMP.", color = TextMid)
                            }
                        }
                    }
                    resolution?.thinkingLevels?.takeIf { it.isNotEmpty() }?.let { thinkingLevels ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ThinkingLevelSlider(thinkingLevels, pending.thinkingLevel, canEdit) {
                            edited = pending.copy(thinkingLevel = it)
                            applyError = null
                        }
                    }
                    ModelPickerActions(
                        dismiss = close,
                        apply = {
                            if (attemptId == null && canEdit && resolution != null &&
                                resolution.unavailable == null && !resolution.changes.isEmpty) {
                                applyError = null
                                attemptId = attempts.start { apply(resolution.changes) }
                            }
                        },
                        error = applyError,
                        unavailable = resolution?.unavailable,
                        unchanged = resolution?.changes?.isEmpty == true,
                        availability = availability,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModelPickerHeader(usage: () -> Unit, refresh: () -> Unit, canUseHost: Boolean, canRefresh: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Models", Modifier.width(IntrinsicSize.Max), style = SheetTitleStyle,
            fontWeight = FontWeight.SemiBold)
        FlowRow(
            Modifier.weight(1f).padding(start = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = rememberHapticOnClick(refresh), enabled = canUseHost && canRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reload")
            }
            TextButton(onClick = rememberHapticOnClick(usage), enabled = canUseHost) {
                Icon(Icons.Outlined.DataUsage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Usage")
            }
        }
    }
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search models" },
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Search models…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        shape = RectangleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Purple400,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            disabledIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = Purple400,
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
private fun CompactModelRow(model: ModelInfo, selected: Boolean, current: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    SmallButtons {
        TextButton(
            onClick = rememberHapticOnClick(onSelect),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp)
                .heightIn(min = SmallButtonHeight)
                .semantics {
                    this.selected = selected
                    contentDescription = "${model.name}, ${model.provider}"
                    if (current) stateDescription = "Current model"
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    else Color.Transparent,
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
}

@Composable
private fun ModelPickerActions(
    dismiss: () -> Unit, apply: () -> Unit, error: String?, unavailable: String?,
    unchanged: Boolean, availability: ModelPickerAvailability,
) {
    val available = availability == ModelPickerAvailability.Available
    val done = unchanged && available
    Column {
        if (error != null) Text(error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (unavailable != null) Text(unavailable, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        availability.message?.let { message ->
            Text(message, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
        SmallButtons {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = rememberHapticOnClick(dismiss),
                    modifier = Modifier.height(SmallButtonHeight),
                    contentPadding = SmallButtonPadding,
                ) { Text(if (availability == ModelPickerAvailability.Applying) "Close" else "Cancel") }
                Button(
                    onClick = rememberHapticOnClick(if (done) dismiss else apply),
                    enabled = available && unavailable == null,
                    modifier = Modifier.height(SmallButtonHeight),
                    contentPadding = SmallButtonPadding,
                ) {
                    Text(if (done) "Done" else "Apply")
                }
            }
        }
    }
}
