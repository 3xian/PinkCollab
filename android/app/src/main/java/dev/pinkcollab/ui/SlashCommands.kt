package dev.pinkcollab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.SlashCommand
import dev.pinkcollab.ui.theme.Base1
import dev.pinkcollab.ui.theme.TextHigh
import dev.pinkcollab.ui.theme.TextMid

/** Arguments (including a trailing space) end command-name completion. */
internal fun slashCommandQuery(draft: String): String? =
    draft.takeIf { it.startsWith('/') && it.none(Char::isWhitespace) }?.substring(1)

internal fun matchingSlashCommands(commands: List<SlashCommand>, query: String): List<SlashCommand> =
    commands.filter { command ->
        command.name.startsWith(query, ignoreCase = true) ||
            command.aliases.orEmpty().any { it.startsWith(query, ignoreCase = true) }
    }

/** A ready result is usable only by the runtime that requested it. */
internal fun currentCommands(state: LoadState<SessionCommands>?, generation: String?): LoadState<SessionCommands>? =
    state.takeUnless { it is LoadState.Ready && it.value.generation != generation }

@Composable
internal fun SlashCommandSuggestions(
    state: LoadState<SessionCommands>?,
    query: String,
    available: Boolean,
    enabled: Boolean,
    retry: () -> Unit,
    select: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth().testTag("slashCommandSuggestions"), color = Base1,
        shape = MaterialTheme.shapes.medium) {
        when {
            !available -> Text("Commands available when OMP is running. You can still send your draft.",
                Modifier.padding(12.dp), color = TextMid, style = MaterialTheme.typography.bodySmall)
            state is LoadState.Failed -> Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Could not load commands: ${state.message}", Modifier.weight(1f),
                    color = TextMid, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                TextButton(onClick = retry) { Text("Retry") }
            }
            state !is LoadState.Ready -> Text("Loading commands…", Modifier.padding(12.dp),
                color = TextMid, style = MaterialTheme.typography.bodySmall)
            else -> {
                val matches = matchingSlashCommands(state.value.commands, query)
                if (matches.isEmpty()) {
                    Text(if (state.value.commands.isEmpty()) "No commands available. You can still send your draft."
                        else "No matching commands. You can still send your draft.",
                        Modifier.padding(12.dp), color = TextMid, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 144.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(matches) { command ->
                            Column(Modifier.fillMaxWidth().testTag("slashCommand:${command.name}")
                                .clickable(enabled = enabled) { select("/${command.name} ") }
                                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("/${command.name}", color = TextHigh, style = MaterialTheme.typography.labelLarge)
                                command.description?.takeIf(String::isNotBlank)?.let {
                                    Text(it, color = TextMid, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                command.input?.hint?.takeIf(String::isNotBlank)?.let {
                                    Text(it, color = TextMid, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
