package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import org.json.JSONObject

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun SessionPage(detail: SessionDetail?, host: HostState?, loading: Boolean, onPrompt: (String, () -> Unit) -> Unit, onCommand: (String) -> Unit, onRespond: (JSONObject) -> Unit, onCycleModel: () -> Unit) {
    if (detail == null) { Text(if (loading) "Loading task…" else "Could not load this task. Go back and try again.", Modifier.padding(24.dp), color = TextMid); return }
    val session = detail.session
    var prompt by rememberSaveable(session.id) { mutableStateOf("") }
    var displayMode by rememberSaveable(session.id) { mutableStateOf(SessionDisplayMode.Concise) }
    val attached = session.runtimeAttached && host?.connected == true
    val displayTimeline = remember(detail.timeline, displayMode) { projectSessionTimeline(detail.timeline, displayMode) }
    val activity = if (host?.connected != true) {
        "Host offline · reconnecting"
    } else if (displayMode == SessionDisplayMode.Debug) {
        session.activity
    } else when (session.status) {
        "starting", "running" -> "Working"
        "needs_input" -> "Waiting for you"
        "completed" -> "Completed"
        "failed" -> "Failed"
        "idle" -> "Idle"
        else -> session.activity
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${host?.paired?.host?.name.orEmpty()} · ${session.cwd}", style = MaterialTheme.typography.bodySmall, color = TextMid)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(session.status)
                    if (host?.connected != true) Text(activity, color = statusColor("failed"), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { displayMode = if (displayMode == SessionDisplayMode.Concise) SessionDisplayMode.Debug else SessionDisplayMode.Concise }, colors = ButtonDefaults.textButtonColors(contentColor = Purple200)) {
                    Text(if (displayMode == SessionDisplayMode.Concise) "Debug" else "Concise")
                }
                val model = detail.model?.takeIf { attached && displayMode == SessionDisplayMode.Debug }
                if (model != null) TextButton(onClick = onCycleModel, enabled = !loading, modifier = Modifier.widthIn(max = 220.dp), colors = ButtonDefaults.textButtonColors(contentColor = Violet400)) {
                    Icon(Icons.Outlined.SwapHoriz, "Switch model")
                    Spacer(Modifier.width(4.dp))
                    Text("${model.provider} · ${model.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (displayTimeline.isEmpty()) item { Text("Waiting for the agent…", style = MaterialTheme.typography.bodySmall, color = TextMid) }
            items(displayTimeline, key = { it.id }) { item -> DisplayItem(item) }
            if (detail.streaming.isNotBlank()) item {
                Card(Modifier.fillMaxWidth().glassPanel(CardShape, fillAlpha = 0.09f), shape = CardShape, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlowDot(Purple400, pulse = true)
                        Spacer(Modifier.width(10.dp))
                        Column { Text("OMP · replying", style = MaterialTheme.typography.labelMedium, color = Purple200); Spacer(Modifier.height(4.dp)); Text(detail.streaming, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            session.attention?.let { attention -> item { AttentionCard(attention, !loading && attached, onRespond) } }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Column(Modifier.fillMaxWidth().padding(12.dp).glassPanel(CardShape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(prompt, { prompt = it }, placeholder = { Text(if (session.status == "running") "Steer OMP…" else "Send another prompt…") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = attached && !loading && session.attention == null, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple400, unfocusedBorderColor = Color.White.copy(alpha = 0.14f), cursorColor = Purple400))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row { TextButton(onClick = { onCommand("interrupt") }, enabled = attached && !loading && session.status in listOf("running", "needs_input"), colors = ButtonDefaults.textButtonColors(contentColor = Violet400)) { Text("Interrupt") }; TextButton(onClick = { onCommand("stop") }, enabled = attached && !loading, colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text("Stop") } }
                GradientButton(onClick = { onPrompt(prompt) { prompt = "" } }, enabled = prompt.isNotBlank() && attached && !loading && session.attention == null) { Text(if (session.status == "running") "Steer" else "Send") }
            }
        }
    }
}

@Composable
private fun DisplayItem(item: SessionDisplayItem) {
    when (item) {
        is SessionDisplayItem.Message -> MessageCard(item)
        is SessionDisplayItem.ActivityGroup -> ActivityGroupCard(item)
        is SessionDisplayItem.Error -> ErrorCard(item)
        is SessionDisplayItem.Raw -> RawTimelineCard(item.item)
    }
}

@Composable
private fun MessageCard(item: SessionDisplayItem.Message) {
    val isUser = item.role == "user"
    Card(
        Modifier
            .fillMaxWidth()
            .then(if (isUser) Modifier.pulsingGlowBorder(CardShape, width = 1.dp) else Modifier)
            .glassPanel(CardShape, fillAlpha = if (isUser) 0.12f else 0.06f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isUser) "You" else "Assistant", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isUser) Purple200 else Violet400)
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ActivityGroupCard(group: SessionDisplayItem.ActivityGroup) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val title = when (group.stage) {
        ActivityStage.Explore -> "Exploring · ${group.operationCount} operations"
        ActivityStage.Change -> if (group.files.isNotEmpty()) {
            "Editing · ${group.files.size} files"
        } else {
            "Editing · ${group.operationCount} operations"
        }
        ActivityStage.Execute -> when (group.status) {
            ActivityStatus.Running -> "Verifying"
            ActivityStatus.Succeeded -> "✓ Verified"
            ActivityStatus.Failed -> "Verification failed"
        }
    }
    // The projection decides expandability: a group carries a detail kind exactly when it has
    // details, so the card does not re-derive the rule from the stage and status.
    val detailKind = group.detailKind
    Card(
        Modifier.fillMaxWidth().glassPanel(CardShape, fillAlpha = 0.045f, borderAlpha = 0.10f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = when (group.status) {
                    ActivityStatus.Failed -> Red400
                    ActivityStatus.Succeeded -> Teal300
                    else -> TextHigh
                },
            )
            if (group.summary.isNotBlank()) Text(group.summary, style = MaterialTheme.typography.bodySmall, color = TextMid)
            if (detailKind != null) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Purple200)) {
                    Text(if (expanded) "Collapse" else detailKind.action)
                }
                if (expanded) Text(group.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
            }
        }
    }
}

private val ActivityDetailKind.action: String
    get() = when (this) {
        ActivityDetailKind.Diff -> "View diff"
        ActivityDetailKind.Content -> "View content"
        ActivityDetailKind.Changes -> "View changes"
        ActivityDetailKind.Error -> "View error"
    }

@Composable
private fun ErrorCard(item: SessionDisplayItem.Error) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .glassPanel(
                CardShape,
                fillAlpha = 0.10f,
                borderBrush = Brush.linearGradient(listOf(Red400.copy(alpha = 0.7f), Red400.copy(alpha = 0.2f))),
            ),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Error", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Red400)
            Text(item.text)
            if (item.details.isNotBlank() && item.details != item.text) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text(if (expanded) "Collapse" else "View error") }
                if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
            }
        }
    }
}

@Composable
private fun RawTimelineCard(item: TimelineItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isDetail = item.kind in listOf("tool", "subagent")
    val detail = item.tool?.let { tool ->
        buildList {
            if (tool.arguments.raw.isNotBlank()) add("Arguments\n${tool.arguments.raw}")
            if (tool.result.isNotBlank()) add("Result\n${tool.result}")
        }.joinToString("\n\n")
    }.orEmpty().ifBlank { item.detail }
    Card(
        Modifier.fillMaxWidth().glassPanel(CardShape, fillAlpha = 0.05f, borderAlpha = 0.10f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (item.kind) { "user" -> "You"; "assistant" -> "Assistant"; "tool" -> "Tool call"; "subagent" -> "Subagent"; "error" -> "Error"; else -> "Activity" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = when (item.kind) { "user" -> Purple200; "error" -> Red400; else -> Violet400 })
            Text(item.text, style = if (isDetail) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            if (isDetail && detail.isNotBlank()) { TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Purple200)) { Text(if (expanded) "Collapse details" else "Expand details") }; if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid) }
        }
    }
}

@Composable
private fun AttentionCard(attention: Attention, enabled: Boolean, respond: (JSONObject) -> Unit) {
    var answer by rememberSaveable(attention.id) { mutableStateOf("") }
    fun response() = JSONObject().put("id", attention.id)
    Card(
        Modifier
            .fillMaxWidth()
            .pulsingGlowBorder(CardShape)
            .glassPanel(CardShape, fillAlpha = 0.12f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlowDot(Purple400, pulse = true)
                Spacer(Modifier.width(8.dp))
                Text("OMP needs your reply", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Purple200)
            }
            Text(attention.text)
            when (attention.type) {
                "select" -> attention.options.forEach { option -> GradientButton(onClick = { respond(response().put("value", option)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(option) } }
                "confirm" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GradientButton(onClick = { respond(response().put("confirmed", true)) }, enabled = enabled) { Text("Confirm") }; OutlinedButton(onClick = { respond(response().put("confirmed", false)) }, enabled = enabled, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMid)) { Text("Decline") } }
                else -> { OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, minLines = if (attention.type == "editor") 4 else 1, modifier = Modifier.fillMaxWidth(), enabled = enabled, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple400, unfocusedBorderColor = Color.White.copy(alpha = 0.14f), cursorColor = Purple400)); GradientButton(onClick = { respond(response().put("value", answer)) }, enabled = enabled) { Text("Submit") } }
            }
            TextButton(onClick = { respond(response().put("cancelled", true)) }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = TextMid)) { Text("Cancel") }
        }
    }
}
