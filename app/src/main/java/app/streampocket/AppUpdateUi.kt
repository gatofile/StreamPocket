package app.streampocket

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppUpdateDialogs(update: AppUpdateController) {
    val context = LocalContext.current
    fun openInstaller() {
        val file = update.readyFile ?: return
        runCatching { context.startActivity(AppUpdateInstaller.intent(context, file)) }.onFailure { update.installFailed() }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller() else update.installPermissionDenied()
    }
    val install: () -> Unit = {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
        else runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            .onFailure { update.installFailed() }
    }
    LaunchedEffect(Unit) { update.onLaunch() }
    LaunchedEffect(update.readyFile) { if (update.readyFile != null) install() }
    if (update.showDialog) AlertDialog(onDismissRequest = update::dismiss, title = { Text("App update") },
        text = {
            when (update.state) {
                AppUpdateState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("Checking for updates…")
                }
                AppUpdateState.CURRENT -> Text("StreamPocket is up to date.")
                AppUpdateState.AVAILABLE -> Text("StreamPocket ${update.release?.version} is available. Update now?")
                AppUpdateState.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Downloading update… ${update.progress}%")
                    LinearProgressIndicator(progress = { update.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                AppUpdateState.READY -> Text(update.installMessage)
                AppUpdateState.CHECK_FAILED -> Text("Couldn't check for app updates. Try again.")
                AppUpdateState.DOWNLOAD_FAILED -> Text("Couldn't download or verify the update. Try again.")
                null -> Unit
            }
        },
        confirmButton = {
            when (update.state) {
                AppUpdateState.CURRENT -> TextButton(update::dismiss) { Text("OK") }
                AppUpdateState.AVAILABLE -> TextButton(update::download) { Text("Update") }
                AppUpdateState.READY -> TextButton(install) { Text("Install") }
                AppUpdateState.CHECK_FAILED -> TextButton({ update.check() }) { Text("Retry") }
                AppUpdateState.DOWNLOAD_FAILED -> TextButton(update::download) { Text("Retry") }
                else -> Unit
            }
        }, dismissButton = {
            when (update.state) {
                AppUpdateState.DOWNLOADING -> TextButton(update::cancelDownload) { Text("Cancel") }
                AppUpdateState.AVAILABLE, AppUpdateState.READY, AppUpdateState.CHECK_FAILED, AppUpdateState.DOWNLOAD_FAILED -> TextButton(update::dismiss) { Text("Later") }
                else -> Unit
            }
        })
}

@Composable
internal fun AppUpdateSetting(update: AppUpdateController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text("App update", fontSize = 15.sp)
            Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (update.state) {
                AppUpdateState.CURRENT -> "Up to date"
                AppUpdateState.AVAILABLE -> "Version ${update.release?.version} available"
                AppUpdateState.CHECKING -> "Checking for updates…"
                AppUpdateState.DOWNLOADING -> "Downloading… ${update.progress}%"
                AppUpdateState.READY -> "Ready to install"
                AppUpdateState.CHECK_FAILED -> "Couldn't check for updates"
                AppUpdateState.DOWNLOAD_FAILED -> "Download failed"
                null -> "Check for a new version"
            }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = {
            if (update.release != null && update.state != AppUpdateState.CHECK_FAILED) update.review() else update.check()
        }, enabled = !update.busy) {
            Text(when (update.state) {
                AppUpdateState.AVAILABLE, AppUpdateState.DOWNLOAD_FAILED -> "Update app"
                AppUpdateState.READY -> "Install update"
                else -> "Check for updates"
            })
        }
    }
}
