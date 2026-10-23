package dev.pinkcollab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pinkcollab.data.QuickInput
import dev.pinkcollab.data.QuickInputStore
import dev.pinkcollab.ui.theme.TextMid
import dev.pinkcollab.ui.theme.rememberHapticOnClick
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.UUID

@Composable
internal fun QuickInputsSheet(
    store: QuickInputStore,
    currentDraft: String,
    canInsert: Boolean,
    onInsert: (String) -> Unit,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<QuickInput>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf(currentDraft) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    fun load() {
        if (busy) return
        busy = true
        scope.launch {
            error = null
            try {
                entries = store.read()
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not load quick inputs"
            } finally {
                busy = false
            }
        }
    }

    fun save(updated: List<QuickInput>, resetEditor: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            error = null
            try {
                store.save(updated)
                entries = updated
                if (resetEditor) {
                    text = ""
                    editingId = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not save quick inputs. Try again."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.matchParentSize().clickable(
                enabled = !busy,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = dismiss,
            ))
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp)
                    .fillMaxWidth().fillMaxHeight(0.85f).semantics { paneTitle = "Quick inputs" },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 16.dp)) {
                            Text("Quick inputs", style = MaterialTheme.typography.titleLarge)
                            Text("Saved on this device · Tap to insert", color = TextMid,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = rememberHapticOnClick(dismiss), enabled = !busy) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close quick inputs")
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("quickInputList")) {
                        if (loaded && entries.isEmpty()) item {
                            Text("Save text below to reuse it in any conversation.",
                                modifier = Modifier.padding(vertical = 16.dp), color = TextMid)
                        }
                        items(entries, key = { it.id }) { entry ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.text,
                                    modifier = Modifier.weight(1f)
                                        .clickable(enabled = canInsert && !busy, role = Role.Button,
                                            onClick = rememberHapticOnClick { onInsert(entry.text) })
                                        .padding(vertical = 14.dp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                IconButton(onClick = rememberHapticOnClick {
                                    editingId = entry.id
                                    text = entry.text
                                }, enabled = !busy) {
                                    Icon(Icons.Outlined.Edit, "Edit quick input", Modifier.size(20.dp))
                                }
                                IconButton(onClick = rememberHapticOnClick {
                                    save(entries.filterNot { it.id == entry.id }, resetEditor = editingId == entry.id)
                                }, enabled = !busy) {
                                    Icon(Icons.Outlined.DeleteOutline, "Delete quick input", Modifier.size(20.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!loaded) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else TextButton(onClick = { load() }) { Text("Retry") }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().testTag("quickInputEditor"),
                        enabled = loaded && !busy,
                        label = { Text(if (editingId == null) "New quick input" else "Edit quick input") },
                        minLines = 2,
                        maxLines = 5,
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (editingId != null) TextButton(onClick = {
                            editingId = null
                            text = ""
                        }, enabled = !busy) { Text("Cancel edit") }
                        Button(
                            enabled = loaded && !busy && text.isNotBlank(),
                            onClick = rememberHapticOnClick {
                                val entry = QuickInput(editingId ?: UUID.randomUUID().toString(), text)
                                val updated = if (editingId == null) entries + entry
                                    else entries.map { if (it.id == editingId) entry else it }
                                save(updated, resetEditor = true)
                            },
                        ) { Text("Save") }
                    }
                }
            }
        }
    }
}
