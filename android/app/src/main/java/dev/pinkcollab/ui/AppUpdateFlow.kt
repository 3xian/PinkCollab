package dev.pinkcollab.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.pinkcollab.BuildConfig
import dev.pinkcollab.data.AppRelease

@Composable
internal fun AppUpdateFlow(
    release: AppRelease,
    download: UpdateDownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    var offeredInstallation by rememberSaveable(release.tag) { mutableStateOf(false) }
    var error by remember(release) { mutableStateOf<String?>(null) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun install() {
        val file = (download as? UpdateDownloadState.Ready)?.file ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            installer.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            error = "No package installer is available"
        } catch (_: Exception) {
            error = "Could not open the installer. Try again."
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else error = "Allow PinkCollab to install apps, then tap Install again."
    }
    fun requestInstall() {
        error = null
        if (context.packageManager.canRequestPackageInstalls()) install()
        else try {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (_: Exception) {
            error = "Could not open installation settings. Allow PinkCollab to install apps in Android settings."
        }
    }
    LaunchedEffect(download is UpdateDownloadState.Ready) {
        if (download !is UpdateDownloadState.Ready) offeredInstallation = false
        else if (!offeredInstallation) {
            offeredInstallation = true
            requestInstall()
        }
    }
    AppUpdateDialog(release, "v${BuildConfig.VERSION_NAME}", onDismiss,
        onUpdate = {
            if (download is UpdateDownloadState.Ready) requestInstall() else onDownload()
        }, download = download, installError = error)
}
