package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.GatewayHttpException
import dev.pinkcollab.data.ToolDetailPage
import dev.pinkcollab.ui.theme.TextMid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal data class ToolDetailState(
    val text: String = "",
    val version: String = "",
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
    val resumeCursor: String? = null,
    val hasMore: Boolean = false,
)

/** Session-scoped cache; only an expanded detail panel initiates a read. */
internal class ToolDetailsController(private val fetch: suspend (String, String?) -> ToolDetailPage) {
    private val entries = mutableStateMapOf<Pair<String, String>, ToolDetailState>()
    private val pinned = mutableMapOf<Pair<String, String>, Int>()
    private val lastReads = mutableMapOf<String, Long>()
    fun state(callId: String, version: String) = entries[callId to version] ?: ToolDetailState()

    fun pin(callId: String, version: String) { val key = callId to version; pinned[key] = (pinned[key] ?: 0) + 1 }
    fun unpin(callId: String, version: String) {
        val key = callId to version
        val count = (pinned[key] ?: 1) - 1
        if (count > 0) pinned[key] = count else pinned.remove(key)
        trim()
    }

    private fun trim() {
        var size = entries.values.sumOf { it.text.length.toLong() * 2 }
        for (key in entries.keys.toList()) {
            if (size <= 4 * 1024 * 1024) break
            val entry = entries[key] ?: continue
            if (key !in pinned && !entry.loading) { entries.remove(key); size -= entry.text.length.toLong() * 2 }
        }
    }

    suspend fun load(callId: String, version: String, more: Boolean = false, retry: Boolean = false) {
        val key = callId to version
        val cached = entries[key]
        val before = cached ?: entries.entries.lastOrNull { it.key.first == callId }?.value
            ?.copy(loading = false, error = null) ?: ToolDetailState()
        if (before.loading || (!more && !retry && before.loaded && entries.containsKey(key))) return
        val cursor = if (more) before.nextCursor ?: before.resumeCursor ?: return
            else if (!retry && !entries.containsKey(key)) before.resumeCursor else null
        entries[key] = before.copy(loading = true, error = null)
        try {
            val now = System.nanoTime() / 1_000_000
            val wait = 500 - (now - (lastReads[callId] ?: (now - 500)))
            if (wait > 0) delay(wait)
            lastReads[callId] = System.nanoTime() / 1_000_000
            var reset = false
            val page = try { fetch(callId, cursor) } catch (failure: GatewayHttpException) {
                if (failure.errorCode != "stale_detail" || cursor == null) throw failure
                reset = true
                fetch(callId, null)
            }
            val append = cursor != null && !reset
            entries[key] = ToolDetailState(
                text = (if (append) before.text else "") + page.text,
                version = page.version, nextCursor = page.nextCursor, loaded = true,
                completed = page.completed, resumeCursor = page.resumeCursor, hasMore = page.hasMore,
            )
            trim()
        } catch (failure: CancellationException) {
            if (cached == null) entries.remove(key) else entries[key] = cached
            throw failure
        } catch (failure: Exception) {
            entries[key] = before.copy(error = failure.message ?: "Could not load tool details")
        }
    }
}

@Composable
internal fun ToolDetailPanel(callId: String, version: String, controller: ToolDetailsController) {
    val state = controller.state(callId, version)
    val scope = rememberCoroutineScope()
    DisposableEffect(callId, version) {
        controller.pin(callId, version)
        onDispose { controller.unpin(callId, version) }
    }
    LaunchedEffect(callId, version) {
        controller.load(callId, version)
        while (true) {
            delay(500)
            val current = controller.state(callId, version)
            if (current.completed) break
            if (current.loaded && !current.hasMore && current.error == null) controller.load(callId, version, more = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.text.isNotBlank()) SelectionContainer {
            Text(state.text, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Loading details…", style = MaterialTheme.typography.labelSmall, color = TextMid)
        }
        if (state.error != null) {
            Text(state.error, style = MaterialTheme.typography.bodySmall, color = TextMid)
            TextButton(onClick = { scope.launch { controller.load(callId, version, more = state.nextCursor != null, retry = true) } }) { Text("Retry") }
        } else if (state.hasMore && !state.loading) {
            TextButton(onClick = { scope.launch { controller.load(callId, version, more = true) } }) { Text("Load more") }
        }
    }
}
