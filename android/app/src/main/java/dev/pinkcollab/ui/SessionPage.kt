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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    if (detail == null) { Text(if (loading) "正在加载任务…" else "无法加载任务，请返回重试。", Modifier.padding(24.dp), color = TextMid); return }
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
            Text(session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${host?.paired?.host?.name.orEmpty()} · ${session.cwd}", style = MaterialTheme.typography.bodySmall, color = TextMid)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(session.status)
                    if (host?.connected != true) Text(activity, color = statusColor("failed"), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { displayMode = if (displayMode == SessionDisplayMode.Concise) SessionDisplayMode.Debug else SessionDisplayMode.Concise }, colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) {
                    Text(if (displayMode == SessionDisplayMode.Concise) "Debug" else "简洁")
                }
                val model = detail.model?.takeIf { attached && displayMode == SessionDisplayMode.Debug }
                if (model != null) TextButton(onClick = onCycleModel, enabled = !loading, modifier = Modifier.widthIn(max = 220.dp), colors = ButtonDefaults.textButtonColors(contentColor = Violet400)) {
                    Icon(Icons.Outlined.SwapHoriz, "切换模型")
                    Spacer(Modifier.width(4.dp))
                    Text("${model.provider} · ${model.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (displayTimeline.isEmpty()) item { Text("等待 Agent 消息…", style = MaterialTheme.typography.bodySmall, color = TextMid) }
            items(displayTimeline, key = { it.id }) { item -> DisplayItem(item) }
            if (detail.streaming.isNotBlank()) item {
                Card(Modifier.fillMaxWidth().glassPanel(CardShape, fillAlpha = 0.09f), shape = CardShape, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlowDot(Pink400, pulse = true)
                        Spacer(Modifier.width(10.dp))
                        Column { Text("OMP · 正在回复", style = MaterialTheme.typography.labelMedium, color = Pink200); Spacer(Modifier.height(4.dp)); Text(detail.streaming, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            session.attention?.let { attention -> item { AttentionCard(attention, !loading && attached, onRespond) } }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Column(Modifier.fillMaxWidth().padding(12.dp).glassPanel(CardShape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(prompt, { prompt = it }, placeholder = { Text(if (session.status == "running") "引导 OMP 调整方向…" else "继续发送 Prompt…") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = attached && !loading && session.attention == null, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pink400, unfocusedBorderColor = Color.White.copy(alpha = 0.14f), cursorColor = Pink400))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row { TextButton(onClick = { onCommand("interrupt") }, enabled = attached && !loading && session.status in listOf("running", "needs_input"), colors = ButtonDefaults.textButtonColors(contentColor = Violet400)) { Text("Interrupt") }; TextButton(onClick = { onCommand("stop") }, enabled = attached && !loading, colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text("Stop") } }
                GradientButton(onClick = { onPrompt(prompt) { prompt = "" } }, enabled = prompt.isNotBlank() && attached && !loading && session.attention == null) { Text(if (session.status == "running") "Steer" else "发送") }
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
            Text(if (isUser) "You" else "Assistant", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isUser) Pink200 else Violet400)
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ActivityGroupCard(group: SessionDisplayItem.ActivityGroup) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val title = when (group.stage) {
        ActivityStage.Explore -> "检查代码 · ${group.operationCount} 个操作"
        ActivityStage.Change -> if (group.files.isNotEmpty()) {
            "修改代码 · ${group.files.size} 个文件"
        } else {
            "修改代码 · ${group.operationCount} 个操作"
        }
        ActivityStage.Execute -> when (group.status) {
            ActivityStatus.Running -> "验证改动"
            ActivityStatus.Succeeded -> "✓ 验证通过"
            ActivityStatus.Failed -> "验证失败"
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
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) {
                    Text(if (expanded) "收起" else detailKind.action)
                }
                if (expanded) Text(group.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
            }
        }
    }
}

private val ActivityDetailKind.action: String
    get() = when (this) {
        ActivityDetailKind.Diff -> "查看 diff"
        ActivityDetailKind.Content -> "查看写入内容"
        ActivityDetailKind.Changes -> "查看修改详情"
        ActivityDetailKind.Error -> "查看错误"
    }

@Composable
private fun ErrorCard(item: SessionDisplayItem.Error) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(Red400.copy(alpha = 0.7f), Red400.copy(alpha = 0.2f))),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            .glassPanel(CardShape, fillAlpha = 0.10f, borderAlpha = 0.16f),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("错误", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Red400)
            Text(item.text)
            if (item.details.isNotBlank() && item.details != item.text) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text(if (expanded) "收起" else "查看错误") }
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
            Text(when (item.kind) { "user" -> "You"; "assistant" -> "Assistant"; "tool" -> "工具调用"; "subagent" -> "Subagent"; "error" -> "错误"; else -> "动态" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = when (item.kind) { "user" -> Pink200; "error" -> Red400; else -> Violet400 })
            Text(item.text, style = if (isDetail) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            if (isDetail && detail.isNotBlank()) { TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Pink200)) { Text(if (expanded) "收起 Details" else "展开 Details") }; if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid) }
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
                GlowDot(Pink400, pulse = true)
                Spacer(Modifier.width(8.dp))
                Text("OMP 需要你回复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Pink200)
            }
            Text(attention.text)
            when (attention.type) {
                "select" -> attention.options.forEach { option -> GradientButton(onClick = { respond(response().put("value", option)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(option) } }
                "confirm" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GradientButton(onClick = { respond(response().put("confirmed", true)) }, enabled = enabled) { Text("确认") }; OutlinedButton(onClick = { respond(response().put("confirmed", false)) }, enabled = enabled, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMid)) { Text("拒绝") } }
                else -> { OutlinedTextField(answer, { answer = it }, label = { Text("你的回答") }, minLines = if (attention.type == "editor") 4 else 1, modifier = Modifier.fillMaxWidth(), enabled = enabled, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pink400, unfocusedBorderColor = Color.White.copy(alpha = 0.14f), cursorColor = Pink400)); GradientButton(onClick = { respond(response().put("value", answer)) }, enabled = enabled) { Text("提交回答") } }
            }
            TextButton(onClick = { respond(response().put("cancelled", true)) }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = TextMid)) { Text("取消输入") }
        }
    }
}
