package dev.pinkcollab.ui

import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.widget.TextView

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import io.noties.markwon.Markwon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TimelineBandBase = Color(0xFF0D0A10)

/**
 * Timeline entries form full-width editorial bands. Their flat geometry keeps
 * the conversation continuous while the subtle tint distinguishes speakers
 * and status without competing with the floating composer.
 */
internal fun Modifier.timelineBand(
    tint: Color = Purple400,
    tintAlpha: Float = 0.025f,
): Modifier = background(TimelineBandBase)
    .background(
        Brush.horizontalGradient(
            listOf(
                tint.copy(alpha = tintAlpha),
                Color.Transparent,
            ),
        ),
    )
    .drawBehind {
        drawLine(
            color = Color.White.copy(alpha = 0.055f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

/**
 * The user's own turn. Separation comes from structure — a brand-gradient rail on the leading
 * edge — never from a fill: [primaryContainer] was the only solid mid-tone surface in a
 * transcript otherwise built from faint tints over [TimelineBandBase], so it read as a banner
 * pasted over the timeline and outshouted the running-state accents. The tint stays inside the
 * band vocabulary and sits at the top of its alpha range, because a user turn is a hard
 * boundary between work phases.
 */
private fun Modifier.userMessageBand(
    primary: Color,
    secondary: Color,
): Modifier = background(TimelineBandBase)
    .background(
        Brush.horizontalGradient(
            0.00f to primary.copy(alpha = 0.11f),
            0.45f to secondary.copy(alpha = 0.05f),
            1.00f to Color.Transparent,
        ),
    )
    .drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(primary.copy(alpha = 0.62f), secondary.copy(alpha = 0.40f)),
            ),
            size = Size(3.dp.toPx(), size.height),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.07f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }


@Composable
internal fun DisplayItem(item: SessionDisplayItem, markwon: Markwon) {
    when (item) {
        is SessionDisplayItem.Message -> MessageCard(item, markwon)
        is SessionDisplayItem.ActivityGroup -> ActivityGroupCard(item)
        is SessionDisplayItem.Error -> ErrorCard(item)
        is SessionDisplayItem.Raw -> RawTimelineCard(item.item, markwon)
    }
}

@Composable
private fun MessageCard(item: SessionDisplayItem.Message, markwon: Markwon) {
    val isUser = item.role == "user"
    val colors = MaterialTheme.colorScheme
    val band = if (isUser) {
        Modifier.userMessageBand(colors.primary, colors.secondary)
    } else {
        Modifier.timelineBand(tint = Teal300, tintAlpha = 0.026f)
    }
    val contentColor = if (isUser) colors.onPrimaryContainer else TextHigh
    Column(
        Modifier
            .fillMaxWidth()
            .then(band)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val time = messageTimeLabel(item.timestamp)
        SpeakerLine(time) {
            if (isUser) YouLabel() else AgentHeader()
        }
        if (isUser) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        } else {
            MarkdownBody(item.text, color = contentColor, markwon)
        }
    }
}

@Composable
private fun YouLabel() {
    Text(
        "You",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = TextHigh,
    )
}

@Composable
private fun SpeakerLine(time: String, title: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { title() }
        if (time.isNotEmpty()) MessageTime(time)
    }
}

@Composable
internal fun AgentHeader(model: ModelInfo? = null, replying: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (replying) "Agent · replying" else "Agent",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = TextHigh,
        )
        model?.let {
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier.weight(1f, fill = false),
                color = Purple400.copy(alpha = 0.14f),
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(
                    it.name.ifBlank { it.id },
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = Purple200,
                )
            }
        }
    }
}

/** Local clock time; a short date is added only when the message is not from today. */
internal fun messageTimeLabel(
    timestamp: String,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return ""
    val local = instant.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val date = local.toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm").format(local)
    return when {
        date == today -> clock
        date.year == today.year -> DateTimeFormatter.ofPattern("M/d HH:mm").format(local)
        else -> DateTimeFormatter.ofPattern("yyyy/M/d HH:mm").format(local)
    }
}

@Composable
private fun MessageTime(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier = modifier,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
        color = TextMid,
    )
}

@Composable
private fun MarkdownBody(markdown: String, color: Color, markwon: Markwon) {
    val rendered = remember(markwon, markdown) { markwon.toMarkdown(markdown) }
    val textColor = color.toArgb()
    AndroidView(
        factory = {
            TextView(it).apply {
                includeFontPadding = false
                typeface = Typeface.SANS_SERIF
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.18f)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        },
        update = { view ->
            if (view.currentTextColor != textColor) view.setTextColor(textColor)
            if (view.tag !== rendered) {
                markwon.setParsedMarkdown(view, rendered)
                view.tag = rendered
            }
        },
    )
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
    val activityTint = when (group.status) {
        ActivityStatus.Failed -> Red400
        ActivityStatus.Succeeded -> Teal300
        ActivityStatus.Running -> Violet400
    }
    // The projection decides expandability: a group carries a detail kind exactly when it has
    // details, so the view does not re-derive the rule from the stage and status.
    val detailKind = group.detailKind
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = activityTint,
                tintAlpha = if (group.status == ActivityStatus.Running) 0.05f else 0.035f,
            )
            .animatedNoiseGradient(
                active = group.status == ActivityStatus.Running,
                tint = activityTint,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = activityTint,
        )
        if (group.summary.isNotBlank()) Text(group.summary, style = MaterialTheme.typography.bodySmall, color = TextMid)
        if (detailKind != null) {
            TextButton(onClick = rememberHapticOnClick { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Purple200)) {
                Text(if (expanded) "Collapse" else detailKind.action)
            }
            if (expanded) Text(group.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
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
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(
                tint = Red400,
                tintAlpha = 0.065f,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Error", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Red400)
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
        if (item.details.isNotBlank() && item.details != item.text) {
            TextButton(onClick = rememberHapticOnClick { expanded = !expanded }, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(contentColor = Red400)) { Text(if (expanded) "Collapse" else "View error") }
            if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMid)
        }
    }
}

@Composable
private fun RawTimelineCard(item: TimelineItem, markwon: Markwon) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isUser = item.kind == "user"
    val colors = MaterialTheme.colorScheme
    val isDetail = item.kind in listOf("tool", "subagent")
    val detail = item.tool?.let { tool ->
        buildList {
            if (tool.arguments.raw.isNotBlank()) add("Arguments\n${tool.arguments.raw}")
            if (tool.result.isNotBlank()) add("Result\n${tool.result}")
        }.joinToString("\n\n")
    }.orEmpty().ifBlank { item.detail }
    val band = if (isUser) {
        Modifier.userMessageBand(colors.primary, colors.secondary)
    } else {
        Modifier.timelineBand(
            tint = when (item.kind) {
                "error" -> Red400
                "assistant" -> Teal300
                else -> Gray400
            },
        )
    }
    val contentColor = if (isUser) colors.onPrimaryContainer else TextHigh
    Column(
        Modifier
            .fillMaxWidth()
            .then(band)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val time = messageTimeLabel(item.timestamp)
        if (!isUser) {
            if (item.kind == "assistant") {
                SpeakerLine(time) { AgentHeader() }
            } else {
                Text(
                    when (item.kind) {
                        "tool" -> "Tool call"
                        "subagent" -> "Subagent"
                        "error" -> "Error"
                        else -> "Activity"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (item.kind == "error") Red400 else TextMid,
                )
            }
        } else {
            SpeakerLine(time) { YouLabel() }
        }
        if (item.kind == "assistant") {
            MarkdownBody(item.text, color = contentColor, markwon)
        } else {
            Text(
                item.text,
                style = if (isDetail) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
        if (isDetail && detail.isNotBlank()) {
            TextButton(
                onClick = rememberHapticOnClick { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Purple200),
            ) {
                Text(if (expanded) "Collapse details" else "Expand details")
            }
            if (expanded) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextMid,
                )
            }
        }
    }
}
