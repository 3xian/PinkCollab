package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.pinkcollab.data.WorkTiming
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.ToolArguments
import dev.pinkcollab.data.ToolTrace
import dev.pinkcollab.ui.theme.Amber300
import dev.pinkcollab.ui.theme.Purple400
import dev.pinkcollab.ui.theme.TextHigh
import dev.pinkcollab.ui.theme.TextMid

internal enum class WorkStatusKind { Sending, Working, Starting, Stopping, Attention, Offline, Ready, History }

internal data class SessionWorkStatus(
    val kind: WorkStatusKind,
    val title: String,
    val detail: String = "",
    val active: Boolean = false,
    val timing: WorkTiming? = null,
)

internal fun sessionWorkStatus(
    detail: SessionDetail,
    host: HostState?,
    sendProgress: SendProgress? = null,
): SessionWorkStatus {
    if (host?.connected == true && sendProgress != null) {
        return when (sendProgress) {
            is SendProgress.Uploading -> SessionWorkStatus(
                WorkStatusKind.Sending,
                "Sending file ${sendProgress.fileIndex} of ${sendProgress.fileCount}",
                sendProgress.fileName,
            )
            SendProgress.Submitting -> SessionWorkStatus(WorkStatusKind.Sending, "Sending message")
        }
    }
    val status = deriveSessionWorkStatus(detail, host)
    val timing = detail.session.workTiming?.takeIf {
        host?.connected == true && detail.session.runtimeAttached &&
            detail.session.status != SessionStatus.Starting
    } ?: return status
    return if (timing.completed && status.kind == WorkStatusKind.Ready) {
        status.copy(title = "Worked for ${workDurationLabel(timing.elapsedMs)}")
    } else if (!timing.completed) status.copy(timing = timing) else status
}

internal fun workDurationLabel(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0) / 1_000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

private fun deriveSessionWorkStatus(detail: SessionDetail, host: HostState?): SessionWorkStatus {
    if (host?.connected != true) {
        val title = when (host?.connection) {
            ConnectionState.Connecting -> "Connecting to host"
            ConnectionState.Synchronizing -> "Syncing with host"
            ConnectionState.AuthenticationRequired -> "Host sign-in required"
            ConnectionState.UpgradeRequired -> "Update required"
            else -> "Host offline"
        }
        return SessionWorkStatus(WorkStatusKind.Offline, title, "Current work cannot be confirmed")
    }
    val session = detail.session
    if (session.origin == dev.pinkcollab.data.SessionOrigin.Discovered) {
        return SessionWorkStatus(WorkStatusKind.History, "History on host", "Send a message to continue")
    }
    val active = session.status == SessionStatus.Running
    if (session.status == SessionStatus.Starting) {
        return SessionWorkStatus(WorkStatusKind.Starting, "Starting agent", active = active)
    }
    if (session.status == SessionStatus.Stopping) {
        return SessionWorkStatus(WorkStatusKind.Stopping, "Stopping agent", active = active)
    }
    if (session.attention != null || session.needsAttention || session.status == SessionStatus.NeedsInput) {
        return SessionWorkStatus(
            WorkStatusKind.Attention,
            "Waiting for your input",
            session.attention?.text?.trim()?.takeIf(String::isNotEmpty) ?: "Review the request in the conversation",
        )
    }
    if (!session.runtimeAttached) return SessionWorkStatus(WorkStatusKind.Ready, "Ready for a message")
    if (!active) return SessionWorkStatus(WorkStatusKind.Ready, "Ready for a message")

    // A new user turn invalidates any unfinished traces left behind by an interrupted turn.
    // Saved history never supplies current work, even when it contains incomplete tools.
    var currentTool: ToolTrace? = null
    var toolCount = 0
    for (index in detail.liveItems.indices.reversed()) {
        val item = detail.liveItems[index]
        if (item.kind == "user") break
        val tool = item.tool
        if (item.kind == "tool" && tool != null && !tool.completed) {
            if (currentTool == null) currentTool = tool
            toolCount++
        }
    }
    currentTool?.let { tool ->
        val family = toolIdentity(tool.name).workFamily
        val label = when (family) {
            ToolFamily.Read -> "Reading"
            ToolFamily.Search -> "Searching"
            ToolFamily.Edit, ToolFamily.Write -> "Editing"
            ToolFamily.Command -> "Running command"
            else -> tool.name.takeIf(String::isNotBlank)?.let { "Using $it" } ?: "Running tool"
        }
        val intent = tool.summary?.action?.takeIf(String::isNotBlank) ?: tool.arguments.firstString("i", "description") ?: label
        val target = tool.summary?.target ?: when (family) {
            ToolFamily.Search -> joinWorkDetail(
                tool.arguments.firstString("query", "pattern"),
                tool.arguments.firstString("path", "url", "cwd"),
            )
            ToolFamily.Command -> tool.arguments.firstString("command", "cmd", "script", "cwd").orEmpty()
            else -> tool.arguments.firstString("path", "file", "filePath", "file_path", "filename", "url", "command", "query", "pattern").orEmpty()
        }
        return SessionWorkStatus(
            WorkStatusKind.Working,
            if (toolCount > 1) "$toolCount tools running" else intent,
            if (toolCount > 1) joinWorkDetail(intent, target)
            else if (intent != label) joinWorkDetail(label, target)
            else target,
            active = true,
        )
    }
    return SessionWorkStatus(
        WorkStatusKind.Working,
        if (detail.streaming.isNotBlank()) "Writing reply" else "Thinking",
        active = true,
    )
}

private fun ToolArguments.firstString(vararg keys: String): String? {
    for (key in keys) {
        val value = strings[key]?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

private fun joinWorkDetail(first: String?, second: String?): String = when {
    first.isNullOrBlank() -> second.orEmpty()
    second.isNullOrBlank() -> first
    else -> "$first  $second"
}

@Composable
internal fun SessionWorkStatusStrip(status: SessionWorkStatus, modifier: Modifier = Modifier) {
    val sendingTextEffect = if (status.kind == WorkStatusKind.Sending) sendingTextShimmer() else Modifier
    val tint = when (status.kind) {
        WorkStatusKind.Attention -> Amber300
        WorkStatusKind.Sending, WorkStatusKind.Working, WorkStatusKind.Starting, WorkStatusKind.Stopping -> Purple400
        else -> TextMid
    }
    Row(
        modifier.fillMaxWidth().testTag("sessionWorkStatus").padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (status.active) {
            ThinkingSquares(
                color = tint,
                modifier = Modifier.padding(top = 1.dp).size(14.dp).clearAndSetSemantics { },
            )
        } else {
            Icon(
                imageVector = when (status.kind) {
                    WorkStatusKind.Attention -> Icons.Outlined.ErrorOutline
                    WorkStatusKind.Offline -> Icons.Outlined.WifiOff
                    WorkStatusKind.Starting, WorkStatusKind.Stopping -> Icons.Outlined.HourglassEmpty
                    else -> Icons.Outlined.ChatBubbleOutline
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Only the stable action label announces changes, never streaming tokens or tool output.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    status.title,
                    modifier = Modifier.weight(1f, fill = false).then(sendingTextEffect)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = TextHigh,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                status.timing?.let { timing ->
                    val elapsed by produceState(timing.elapsedAt(), timing) {
                        value = timing.elapsedAt()
                        while (timing.running && !timing.completed) {
                            delay(1_000)
                            value = timing.elapsedAt()
                        }
                    }
                    Text(workDurationLabel(elapsed), color = TextMid, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1)
                }
            }
            if (status.detail.isNotBlank()) Text(
                status.detail,
                color = TextMid,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A neutral highlight sweeps over the glyphs; animation only invalidates drawing. */
@Composable
private fun sendingTextShimmer(): Modifier {
    val phase = rememberInfiniteTransition(label = "sendingTextShimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "sendingTextHighlight",
    )
    return Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val center = size.width * phase.value
            val halfWidth = size.width * 0.35f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(TextMid, Color.White, TextMid),
                    start = Offset(center - halfWidth, 0f),
                    end = Offset(center + halfWidth, 0f),
                ),
                blendMode = BlendMode.SrcIn,
            )
        }
}

/** Four fixed tiles light up clockwise; animation state is read only during drawing. */
@Composable
private fun ThinkingSquares(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinkingSquares")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "thinkingSquarePhase",
    )
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val edge = (size.minDimension - gap) / 2f
        val step = edge + gap
        for (index in 0..3) {
            // Clockwise order: top-left, top-right, bottom-right, bottom-left.
            val x = if (index == 1 || index == 2) step else 0f
            val y = if (index >= 2) step else 0f
            val age = (phase.value - index + 4f) % 4f
            val brightness = (1f - age / 2f).coerceIn(0f, 1f)
            drawRoundRect(
                color = color.copy(alpha = 0.18f + 0.82f * brightness),
                topLeft = Offset(x, y),
                size = Size(edge, edge),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}
