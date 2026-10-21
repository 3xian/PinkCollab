package dev.pinkcollab.ui

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Code
import org.commonmark.node.StrongEmphasis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TimelineBandBase = Color(0xFF0D0A10)

/** Error and raw activity bands retain a restrained tint outside the message surfaces. */
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

/** Inset prompts and open replies share the same quiet conversation rhythm. */
internal fun Modifier.messageSurface(isUser: Boolean = false): Modifier = fillMaxWidth()
    .padding(horizontal = 12.dp, vertical = if (isUser) 8.dp else 12.dp)
    .then(if (isUser) Modifier.background(SessionColors.userSurface, RoundedCornerShape(12.dp))
        .padding(12.dp) else Modifier)


@Composable
internal fun DisplayItem(item: SessionDisplayItem, renderer: SessionMarkdownRenderer, liveActivity: Boolean = false, toolDetails: ToolDetailsController? = null, liveOperationIds: Set<String>? = null, onInspectActivity: () -> Unit = {}, collapseKey: String? = null) {
    when (item) {
        is SessionDisplayItem.Message -> MessageCard(item, renderer)
        is SessionDisplayItem.ActivityGroup -> ActivityTimeline(item, liveActivity, liveOperationIds, onInspectActivity, collapseKey)
        is SessionDisplayItem.Error -> ErrorCard(item)
        is SessionDisplayItem.Feedback -> FeedbackCard(item)
        is SessionDisplayItem.Raw -> RawTimelineCard(item.item, renderer, toolDetails)
    }
}

@Composable
private fun MessageCard(item: SessionDisplayItem.Message, renderer: SessionMarkdownRenderer) {
    val isUser = item.role == "user"
    Column(
        Modifier.messageSurface(isUser),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val time = messageTimeLabel(item.timestamp)
        SpeakerLine(time) {
            if (isUser) YouLabel() else AgentHeader()
        }
        TimelineMessageBody(item.text, captureTouch = isUser) { onCopy ->
            if (isUser) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
            } else {
                MarkdownBody(item.id, item.text, color = TextHigh, renderer, onCopy)
            }
        }
    }
}

@Composable
private fun FeedbackCard(item: SessionDisplayItem.Feedback) {
    Column(
        Modifier.messageSurface(isUser = true),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SpeakerLine(messageTimeLabel(item.timestamp)) {
            SpeakerTitle("Your response", SessionColors.user)
        }
        if (item.question.isNotBlank()) {
            Text(item.question, style = MaterialTheme.typography.bodyMedium, color = TextMid)
        }
        TimelineMessageBody(item.answer, copyable = item.answer.isNotEmpty()) { _ ->
            Text(
                item.answer.ifEmpty { "(Empty response)" },
                style = MaterialTheme.typography.bodyMedium,
                color = TextHigh,
            )
        }
    }
}

@Composable
private fun YouLabel() {
    SpeakerTitle("You", SessionColors.user)
}

@Composable
private fun SpeakerTitle(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Default,
        color = color,
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeakerTitle("Agent", SessionColors.agent,
            if (replying) workTextShimmer().semantics { contentDescription = "Agent replying" } else Modifier)
        model?.let {
            Text(it.name.ifBlank { it.id }, Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Default,
                color = Gray400, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        fontFamily = FontFamily.Default,
        color = Gray400,
    )
}

internal fun createSessionMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                val maple = ResourcesCompat.getFont(context, dev.pinkcollab.R.font.maple_mono_cn_regular) ?: Typeface.DEFAULT
                builder.codeTypeface(maple).codeBlockTypeface(maple)
            }

            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(Code::class.java) { visitor, code ->
                    var color = Purple200
                    var ancestor = code.parent
                    while (ancestor != null) {
                        if (ancestor is StrongEmphasis) {
                            color = BrandPink
                            break
                        }
                        ancestor = ancestor.parent
                    }
                    // Replace CodeSpan with foreground only: inherit font and size,
                    // and resolve emphasis from syntax rather than shared paint state.
                    val start = visitor.length()
                    visitor.builder().append(code.literal)
                    visitor.setSpans(start, ForegroundColorSpan(color.toArgb()))
                }
            }

            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                builder.appendFactory(StrongEmphasis::class.java) { _, _ ->
                    ForegroundColorSpan(BrandPink.toArgb())
                }
            }
        })
        .build()

@Composable
private fun MarkdownBody(
    messageId: String,
    markdown: String,
    color: Color,
    renderer: SessionMarkdownRenderer,
    onCopy: (() -> Unit)?,
) {
    var rendered by remember(renderer, messageId) { mutableStateOf<Spanned?>(null) }
    LaunchedEffect(renderer, messageId, markdown) { rendered = renderer.render(markdown) }
    val textColor = color.toArgb()
    val textSizeSp = MaterialTheme.typography.bodyMedium.fontSize.value
    AndroidView(
        factory = { context ->
            MessageBodyTextView(context).apply {
                includeFontPadding = false
                typeface = ResourcesCompat.getFont(context, dev.pinkcollab.R.font.maple_mono_cn_regular) ?: Typeface.DEFAULT
                isLongClickable = onCopy != null
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.18f)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            }
        },
        update = { view ->
            view.onCopy = onCopy
            view.isLongClickable = onCopy != null
            if (view.currentTextColor != textColor) view.setTextColor(textColor)
            val textSizePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp, view.resources.displayMetrics,
            )
            if (view.textSize != textSizePx) {
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            }
            val content = rendered ?: markdown
            if (view.tag !== content) {
                val parsed = rendered
                if (parsed == null) view.text = markdown
                else renderer.markwon.setParsedMarkdown(view, parsed)
                view.tag = content
            }
        },
    )
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
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Error", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = Red400)
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
        if (item.details.isNotBlank() && item.details != item.text) {
            DetailToggle("Error details", expanded, onClick = { expanded = !expanded }, tint = Red400)
            if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
    }
}

@Composable
private fun RawTimelineCard(item: TimelineItem, renderer: SessionMarkdownRenderer, toolDetails: ToolDetailsController?) {
    if (item.kind == "feedback") {
        FeedbackCard(SessionDisplayItem.Feedback(item.id, item.detail, item.text, item.timestamp))
        return
    }
    if (item.kind == "user" || item.kind == "assistant") {
        MessageCard(SessionDisplayItem.Message(item.id, item.kind, item.text, item.timestamp), renderer)
        return
    }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val isDetail = item.kind in listOf("tool", "subagent")
    val detail = item.tool?.let { tool ->
        buildList {
            if (tool.arguments.raw.isNotBlank()) add("Arguments\n${tool.arguments.raw}")
            if (tool.result.isNotBlank()) add("Result\n${tool.result}")
        }.joinToString("\n\n")
    }.orEmpty().ifBlank { item.detail }
    Column(
        Modifier.fillMaxWidth().timelineBand(tint = if (item.kind == "error") Red400 else Gray400).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when (item.kind) {
                "tool" -> "Tool call"
                "subagent" -> "Subagent"
                "error" -> "Error"
                else -> "Activity"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Default,
            color = if (item.kind == "error") Red400 else TextMid,
        )
        TimelineMessageBody(item.text, copyable = false, captureTouch = true) { _ ->
            Text(item.text, style = if (isDetail) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = TextHigh)
        }
        if (isDetail && (detail.isNotBlank() || item.tool?.detailsAvailable == true)) {
            DetailToggle("Details", expanded, onClick = { expanded = !expanded })
            if (expanded) {
                if (item.tool?.detailsAvailable == true && toolDetails != null) {
                    ToolDetailPanel(item.tool.callId, item.tool.detailsVersion, toolDetails)
                } else Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
        }
    }
}
