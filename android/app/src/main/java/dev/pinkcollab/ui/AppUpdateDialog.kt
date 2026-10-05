package dev.pinkcollab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
    download: UpdateDownloadState = UpdateDownloadState.Idle,
    installError: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PinkCollab update available") },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                Text("Current version: $currentVersion")
                Text("Latest version: ${release.tag}")
                Spacer(Modifier.height(16.dp))
                Text("Release notes", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    release.notes.ifBlank { "No release notes provided." },
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(16.dp))
                when (download) {
                    is UpdateDownloadState.Downloading -> {
                        if (download.total > 0) {
                            val fraction = (download.received.toFloat() / download.total).coerceIn(0f, 1f)
                            Text("Downloading: ${(fraction * 100).toInt()}%")
                            LinearProgressIndicator(progress = { fraction })
                        } else {
                            Text("Downloading update…")
                            LinearProgressIndicator()
                        }
                    }
                    is UpdateDownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error)
                    is UpdateDownloadState.Ready -> Text("Download complete. Confirm installation in Android.")
                    UpdateDownloadState.Idle -> Text(if (release.apkUrl == null) "No APK is available for this release." else
                        "Download in the app, then confirm installation in Android. You may need to allow PinkCollab to install apps.")
                }
                installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = release.apkUrl != null && download !is UpdateDownloadState.Downloading) {
                Text(when (download) {
                    is UpdateDownloadState.Ready -> "Install"
                    is UpdateDownloadState.Failed -> "Retry"
                    else -> "Update"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) {
            Text(if (download is UpdateDownloadState.Downloading) "Cancel download" else "Later")
        } },
    )
}
