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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import org.json.JSONObject

@Composable
fun SessionPage(detail: SessionDetail?, host: HostState?, loading: Boolean, onPrompt: (String, () -> Unit) -> Unit, onCommand: (String) -> Unit, onRespond: (JSONObject) -> Unit, onCycleModel: () -> Unit) {
    if (detail == null) { Text(if (loading) "正在加载任务…" else "无法加载任务，请返回重试。", Modifier.padding(24.dp)); return }
    val session = detail.session
    var prompt by rememberSaveable(session.id) { mutableStateOf("") }
    val attached = session.runtimeAttached && host?.connected == true
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleLarge)
            Text("${host?.paired?.host?.name.orEmpty()} · ${session.cwd}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (host?.connected != true) "Host 离线 · 正在尝试重连" else session.activity, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                val model = detail.model?.takeIf { attached }
                if (model != null) TextButton(onClick = onCycleModel, enabled = !loading, modifier = Modifier.widthIn(max = 220.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, "切换模型")
                    Spacer(Modifier.width(4.dp))
                    Text("${model.provider} · ${model.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail.timeline.isEmpty()) item { Text("等待 Agent 消息…", style = MaterialTheme.typography.bodySmall) }
            items(detail.timeline, key = { it.id }) { item -> TimelineCard(item) }
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
private fun TimelineCard(item: TimelineItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isDetail = item.kind in listOf("tool", "subagent")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (item.kind == "user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (item.kind) { "user" -> "You"; "assistant" -> "OMP"; "tool" -> "工具调用"; "subagent" -> "Subagent"; "error" -> "错误"; else -> "动态" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(item.text, style = if (isDetail) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            if (isDetail && item.detail.isNotBlank()) { TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起 Details" else "展开 Details") }; if (expanded) Text(item.detail, style = MaterialTheme.typography.bodySmall) }
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
