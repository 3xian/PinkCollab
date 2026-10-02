package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pinkcollab.ui.theme.rememberHapticOnClick
import dev.pinkcollab.data.UsageAccount
import dev.pinkcollab.data.UsageLimit
import dev.pinkcollab.data.UsageSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun UsageSheet(state: LoadState<UsageSnapshot>?, reload: () -> Unit, back: () -> Unit) {
    Dialog(onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 640.dp).fillMaxHeight(0.93f)
                .semantics { paneTitle = "Provider usage" },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = rememberHapticOnClick(back)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to models")
                        }
                        Text("Usage", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = rememberHapticOnClick(reload), enabled = state != LoadState.Loading) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Reload")
                        }
                    }
                    when (state) {
                        null, LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        is LoadState.Failed -> Column(Modifier.fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = rememberHapticOnClick(reload)) { Text("Retry") }
                        }
                        is LoadState.Ready -> LazyColumn(
                            Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item {
                                Text("Account quotas shared across conversations", style = MaterialTheme.typography.bodyMedium)
                                Text("Checked ${usageTime(state.value.generatedAt)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.value.accounts.isEmpty()) item { Text("No provider accounts with usage data.") }
                            state.value.accounts.groupBy { it.provider }.forEach { (provider, accounts) ->
                                item { Text(provider, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                                accounts.forEach { account -> item { UsageAccountCard(account) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageAccountCard(account: UsageAccount) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top) {
                Text(listOfNotNull(account.accountLabel, account.plan).joinToString(" · "),
                    modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                account.fetchedAt?.let {
                    Text("Updated ${usageClockTime(it)}", maxLines = 1,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (account.status) {
                "disabled" -> Text("Account disabled. Reconnect it on the host.", color = MaterialTheme.colorScheme.error)
                "unavailable" -> Text("Usage data unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            account.limits.forEach { UsageLimitRow(it) }
        }
    }
}

@Composable
private fun UsageLimitRow(limit: UsageLimit) {
    val color = when (limit.status) {
        "exhausted" -> MaterialTheme.colorScheme.error
        "warning" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(listOfNotNull(limit.label, limit.modelId, limit.tier).distinct().joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium)
        if (limit.windowLabel != null && !limit.label.contains(limit.windowLabel, ignoreCase = true)) {
            Text(limit.windowLabel, style = MaterialTheme.typography.bodySmall)
        }
        val absoluteAmount = usageAbsoluteAmount(limit)
        if (absoluteAmount != null || limit.usedFraction == null) {
            Text(absoluteAmount ?: "Usage unknown", color = color, style = MaterialTheme.typography.bodyMedium)
        }
        limit.usedFraction?.let { fraction ->
            val progress = fraction.toFloat().coerceIn(0f, 1f)
            val fillColor = when (limit.status) {
                "exhausted" -> MaterialTheme.colorScheme.errorContainer
                "warning" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .semantics(mergeDescendants = true) {
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(fillColor))
                }
                Text(String.format(Locale.US, "%.1f%% used", fraction * 100),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (limit.status == "warning") Text("Near quota limit", color = color, style = MaterialTheme.typography.bodySmall)
        if (limit.status == "exhausted") Text("Quota exhausted", color = color, style = MaterialTheme.typography.bodySmall)
        limit.resetsAt?.let { Text("Resets ${usageTime(it)}", style = MaterialTheme.typography.bodySmall) }
    }
}
internal fun usageAmount(limit: UsageLimit): String {
    val percentage = limit.usedFraction?.let { String.format(Locale.US, "%.1f%% used", it * 100) }
    return listOfNotNull(percentage, usageAbsoluteAmount(limit)).joinToString(" · ").ifEmpty { "Usage unknown" }
}
private fun usageAbsoluteAmount(limit: UsageLimit): String? =
    if (limit.unit !in listOf(null, "percent", "unknown")) {
        when {
            limit.used != null && limit.limit != null -> "${usageNumber(limit.used)} / ${usageNumber(limit.limit)} ${limit.unit}"
            limit.remaining != null -> "${usageNumber(limit.remaining)} ${limit.unit} remaining"
            limit.used != null -> "${usageNumber(limit.used)} ${limit.unit} used"
            else -> null
        }
    } else null
private fun usageNumber(value: Double): String = java.text.NumberFormat.getNumberInstance().format(value)
private fun usageTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))
private fun usageClockTime(value: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(value))
