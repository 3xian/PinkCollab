package dev.pinkcollab.ui

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.method.LinkMovementMethod
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * The user's own turn. Separation comes from structure: a brand-gradient rail on the leading
 * edge, never a fill. [primaryContainer] was the only solid mid-tone surface in a
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
internal fun DisplayItem(item: SessionDisplayItem, renderer: SessionMarkdownRenderer, liveActivity: Boolean = false) {
    when (item) {
        is SessionDisplayItem.Message -> MessageCard(item, renderer)
        is SessionDisplayItem.ActivityGroup -> ActivityGroupCard(item, liveActivity)
        is SessionDisplayItem.Error -> ErrorCard(item)
        is SessionDisplayItem.Raw -> RawTimelineCard(item.item, renderer)
    }
}

@Composable
private fun MessageCard(item: SessionDisplayItem.Message, renderer: SessionMarkdownRenderer) {
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
            MarkdownBody(item.id, item.text, color = contentColor, renderer)
        }
    }
}

@Composable
private fun YouLabel() {
    SpeakerTitle("You", Purple400)
}

@Composable
private fun SpeakerTitle(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(end = 6.dp), // Keeps the synthesized italic overhang clear of the model chip.
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SpeakerTitle(if (replying) "Agent replying" else "Agent", BrandPink)
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
                    fontFamily = FontFamily.Default,
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
        fontFamily = FontFamily.Default,
        color = TextMid,
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
private fun MarkdownBody(messageId: String, markdown: String, color: Color, renderer: SessionMarkdownRenderer) {
    var rendered by remember(renderer, messageId) { mutableStateOf<Spanned?>(null) }
    LaunchedEffect(renderer, messageId, markdown) { rendered = renderer.render(markdown) }
    val textColor = color.toArgb()
    val textSizeSp = MaterialTheme.typography.bodyMedium.fontSize.value
    AndroidView(
        factory = {
            TextView(it).apply {
                includeFontPadding = false
                typeface = ResourcesCompat.getFont(it, dev.pinkcollab.R.font.maple_mono_cn_regular) ?: Typeface.DEFAULT
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.18f)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            }
        },
        update = { view ->
            if (view.currentTextColor != textColor) view.setTextColor(textColor)
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
private fun ActivityGroupCard(group: SessionDisplayItem.ActivityGroup, liveActivity: Boolean) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val activityTint = activityColor(group.status)
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "activityArrow")
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(tint = activityTint, tintAlpha = 0.025f)
            .animateContentSize()
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse activity" else "Expand activity",
                onClick = rememberHapticOnClick { expanded = !expanded },
            )
            .semantics {
                contentDescription = if (expanded) "Collapse activity" else "Expand activity"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    group.action,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    color = TextHigh,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.summary.isNotBlank()) {
                    Text(group.summary, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ActivityOperationIcons(group, liveActivity)
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(22.dp).rotate(arrow),
                tint = TextMid,
            )
        }
        if (expanded) {
            group.operations.forEach { operation ->
                key(operation.id) { ActivityOperationDetails(operation, initiallyExpanded = group.operationCount == 1, liveActivity = liveActivity) }
            }
        }
    }
}

@Composable
private fun ActivityOperationIcons(group: SessionDisplayItem.ActivityGroup, liveActivity: Boolean) {
    val rowState = rememberLazyListState()
    val entranceDurationMillis = 280
    // Completion deadlines belong to the batch, not to lazy item composition.
    val entranceDeadlines = remember(group.id) { HashMap<String, Long>() }
    val batchStartMillis = SystemClock.uptimeMillis()
    var entranceIndex = 0
    for (operation in group.operations) {
        if (operation.id !in entranceDeadlines) {
            entranceDeadlines[operation.id] = batchStartMillis + entranceIndex++ * 70L + entranceDurationMillis
        }
    }
    var previousCount by remember(group.id) { mutableIntStateOf(group.operations.size) }
    LaunchedEffect(group.operations.size) {
        val grew = group.operations.size > previousCount
        previousCount = group.operations.size
        if (grew) rowState.animateScrollToItem(group.operations.lastIndex)
    }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((-4).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(group.operations, key = { it.id }) { operation ->
            val deadlineMillis = entranceDeadlines.getValue(operation.id)
            val entrance = remember(operation.id) {
                Animatable(if (SystemClock.uptimeMillis() >= deadlineMillis) 1f else 0f)
            }
            LaunchedEffect(entrance) {
                val remainingMillis = (deadlineMillis - SystemClock.uptimeMillis()).coerceAtLeast(0)
                if (remainingMillis == 0L) {
                    entrance.snapTo(1f)
                } else {
                    entrance.animateTo(
                        1f,
                        tween(
                            durationMillis = remainingMillis.coerceAtMost(entranceDurationMillis.toLong()).toInt(),
                            delayMillis = (remainingMillis - entranceDurationMillis).coerceAtLeast(0).toInt(),
                        ),
                    )
                }
            }
            Box(
                Modifier.animateItem(fadeInSpec = null, placementSpec = tween(280))
                    .graphicsLayer {
                        alpha = entrance.value
                    },
            ) {
                ActivityStatusIcon(operation.status, liveActivity, operation.action)
            }
        }
    }
}

@Composable
private fun ActivityOperationDetails(operation: ActivityOperation, initiallyExpanded: Boolean, liveActivity: Boolean) {
    var expanded by rememberSaveable(operation.id) { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.025f), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${operation.name}  ${activityStatusLabel(operation.status, liveActivity)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Default, color = activityColor(operation.status))
        Text(operation.action, style = MaterialTheme.typography.bodySmall, color = TextHigh)
        if (operation.target.isNotBlank()) {
            Text(operation.target, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        operation.detailKind?.let { kind ->
            DetailToggle(kind.action, expanded, onClick = { expanded = !expanded })
            if (expanded) {
                SelectionContainer {
                    Text(
                        operation.details,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityStatusIcon(status: ActivityStatus, liveActivity: Boolean, action: String) {
    val description = "$action, ${activityStatusLabel(status, liveActivity)}"
    if (status == ActivityStatus.Running && liveActivity) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp).semantics { contentDescription = description },
            color = activityColor(status),
            strokeWidth = 2.dp,
        )
    } else {
        val icon = when (status) {
            ActivityStatus.Failed -> Icons.Outlined.ErrorOutline
            ActivityStatus.Succeeded -> Icons.Outlined.Check
            ActivityStatus.Running -> Icons.Outlined.HourglassEmpty
        }
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = activityColor(status),
        )
    }
}

private fun activityColor(status: ActivityStatus): Color = when (status) {
    ActivityStatus.Running -> Violet400
    ActivityStatus.Succeeded -> Teal300
    ActivityStatus.Failed -> Red400
}

private fun activityStatusLabel(status: ActivityStatus, liveActivity: Boolean): String =
    if (status == ActivityStatus.Running && !liveActivity) "Last seen running" else status.label

private val ActivityStatus.label: String
    get() = when (this) {
        ActivityStatus.Running -> "Running"
        ActivityStatus.Succeeded -> "Completed"
        ActivityStatus.Failed -> "Failed"
    }

private val ActivityDetailKind.action: String
    get() = when (this) {
        ActivityDetailKind.Diff -> "Diff"
        ActivityDetailKind.Content -> "Content"
        ActivityDetailKind.Changes -> "Changes"
        ActivityDetailKind.Error -> "Error details"
        ActivityDetailKind.Operation -> "Arguments & output"
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
        Text("Error", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = Red400)
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = TextHigh)
        if (item.details.isNotBlank() && item.details != item.text) {
            DetailToggle("Error details", expanded, onClick = { expanded = !expanded }, tint = Red400)
            if (expanded) Text(item.details, style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
    }
}

@Composable
private fun RawTimelineCard(item: TimelineItem, renderer: SessionMarkdownRenderer) {
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
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.Default,
                    color = if (item.kind == "error") Red400 else TextMid,
                )
            }
        } else {
            SpeakerLine(time) { YouLabel() }
        }
        if (item.kind == "assistant") {
            MarkdownBody(item.id, item.text, color = contentColor, renderer)
        } else {
            Text(
                item.text,
                style = if (isDetail) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
        if (isDetail && detail.isNotBlank()) {
            DetailToggle("Details", expanded, onClick = { expanded = !expanded })
            if (expanded) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
        }
    }
}
