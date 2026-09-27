package dev.pinkcollab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.AppRelease

@Composable
internal fun AppUpdateDialog(
    release: AppRelease,
    currentVersion: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PinkCollab update available") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Current version: $currentVersion")
                Text("Latest version: ${release.tag}")
                Spacer(Modifier.height(16.dp))
                Text("Release notes", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(release.notes.ifBlank { "No release notes provided." })
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
