package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import org.json.JSONObject

@Composable
fun SessionPage(detail: SessionDetail?, host: HostState?, loading: Boolean, onPrompt: (String, () -> Unit) -> Unit, onCommand: (String) -> Unit, onRespond: (JSONObject) -> Unit, onCycleModel: () -> Unit) {
    if (detail == null) { Text(if (loading) "正在加载任务…" else "无法加载任务，请返回重试。", Modifier.padding(24.dp)); return }
    val session = detail.session
    var prompt by rememberSaveable(session.id) { mutableStateOf("") }
    var displayMode by rememberSaveable(session.id) { mutableStateOf(SessionDisplayMode.Concise) }
    val attached = session.runtimeAttached && host?.connected == true
    val displayTimeline = remember(detail.timeline, displayMode) { projectSessionTimeline(detail.timeline, displayMode) }
    val activity = if (host?.connected != true) {
        "Host 离线 · 正在尝试重连"
    } else if (displayMode == SessionDisplayMode.Debug) {
        session.activity
    } else when (session.status) {
        "starting", "running" -> "正在处理"
        "needs_input" -> "等待你的回复"
        "completed" -> "已完成"
        "failed" -> "运行失败"
        "idle" -> "待命"
        else -> session.activity
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleLarge)
            Text("${host?.paired?.host?.name.orEmpty()} · ${session.cwd}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(activity, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { displayMode = if (displayMode == SessionDisplayMode.Concise) SessionDisplayMode.Debug else SessionDisplayMode.Concise }) {
                    Text(if (displayMode == SessionDisplayMode.Concise) "Debug" else "简洁")
                }
                val model = detail.model?.takeIf { attached && displayMode == SessionDisplayMode.Debug }
                if (model != null) TextButton(onClick = onCycleModel, enabled = !loading, modifier = Modifier.widthIn(max = 220.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, "切换模型")
                    Spacer(Modifier.width(4.dp))
                    Text("${model.provider} · ${model.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (displayTimeline.isEmpty()) item { Text("等待 Agent 消息…", style = MaterialTheme.typography.bodySmall) }
            items(displayTimeline, key = { it.id }) { item -> DisplayItem(item) }
            if (detail.streaming.isNotBlank()) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("OMP · 正在回复", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(8.dp)); Text(detail.streaming) } } }
            session.attention?.let { attention -> item { AttentionCard(attention, !loading && attached, onRespond) } }
        }
        HorizontalDivider()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(prompt, { prompt = it }, placeholder = { Text(if (session.status == "running") "引导 OMP 调整方向…" else "继续发送 Prompt…") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = attached && !loading && session.attention == null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row { TextButton(onClick = { onCommand("interrupt") }, enabled = attached && !loading && session.status in listOf("running", "needs_input")) { Text("Interrupt") }; TextButton(onClick = { onCommand("stop") }, enabled = attached && !loading) { Text("Stop") } }
                Button(onClick = { onPrompt(prompt) { prompt = "" } }, enabled = prompt.isNotBlank() && attached && !loading && session.attention == null) { Text(if (session.status == "running") "Steer" else "发送") }
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (item.role == "user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (item.role == "user") "You" else "Assistant", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ActivityGroupCard(group: SessionDisplayItem.ActivityGroup) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val title = when (group.stage) {
        ActivityStage.Explore -> "检查代码 · ${group.operationCount} 个操作"
        ActivityStage.Change -> "修改代码 · ${group.files.size.takeIf { it > 0 } ?: group.operationCount} 个文件"
        ActivityStage.Execute -> when (group.status) {
            ActivityStatus.Running -> "验证改动"
            ActivityStatus.Succeeded -> "✓ 验证通过"
            ActivityStatus.Failed -> "验证失败"
        }
    }
    val expandable = group.details.isNotBlank() &&
        (group.stage == ActivityStage.Change || group.status == ActivityStatus.Failed)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (group.status == ActivityStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (group.summary.isNotBlank()) Text(group.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expandable) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "收起" else if (group.stage == ActivityStage.Change) "查看 diff" else "查看错误")
                }
                if (expanded) Text(group.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ErrorCard(item: SessionDisplayItem.Error) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("错误", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(item.text)
            if (item.details.isNotBlank() && item.details != item.text) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "查看错误") }
                if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
            if (tool.arguments.isNotBlank() && tool.arguments != "null") add("Arguments\n${tool.arguments}")
            if (tool.result.isNotBlank()) add("Result\n${tool.result}")
        }.joinToString("\n\n")
    }.orEmpty().ifBlank { item.detail }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (item.kind == "user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (item.kind) { "user" -> "You"; "assistant" -> "Assistant"; "tool" -> "工具调用"; "subagent" -> "Subagent"; "error" -> "错误"; else -> "动态" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(item.text, style = if (isDetail) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            if (isDetail && detail.isNotBlank()) { TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起 Details" else "展开 Details") }; if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun AttentionCard(attention: Attention, enabled: Boolean, respond: (JSONObject) -> Unit) {
    var answer by rememberSaveable(attention.id) { mutableStateOf("") }
    fun response() = JSONObject().put("id", attention.id)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("OMP 需要你回复", style = MaterialTheme.typography.titleMedium)
            Text(attention.text)
            when (attention.type) {
                "select" -> attention.options.forEach { option -> OutlinedButton(onClick = { respond(response().put("value", option)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(option) } }
                "confirm" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { respond(response().put("confirmed", true)) }, enabled = enabled) { Text("确认") }; OutlinedButton(onClick = { respond(response().put("confirmed", false)) }, enabled = enabled) { Text("拒绝") } }
                else -> { OutlinedTextField(answer, { answer = it }, label = { Text("你的回答") }, minLines = if (attention.type == "editor") 4 else 1, modifier = Modifier.fillMaxWidth(), enabled = enabled); Button(onClick = { respond(response().put("value", answer)) }, enabled = enabled) { Text("提交回答") } }
            }
            TextButton(onClick = { respond(response().put("cancelled", true)) }, enabled = enabled) { Text("取消输入") }
        }
    }
}
