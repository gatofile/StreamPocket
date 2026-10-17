package app.streampocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File

enum class AppUpdateState { CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, CHECK_FAILED, DOWNLOAD_FAILED }

class AppUpdateController(private val source: AppUpdateSource, private val scope: CoroutineScope) {
    var state by mutableStateOf<AppUpdateState?>(null); private set
    var showDialog by mutableStateOf(false); private set
    var release by mutableStateOf<AppRelease?>(null); private set
    var progress by mutableIntStateOf(0); private set
    var readyFile by mutableStateOf<File?>(null); private set
    var installMessage by mutableStateOf("Update downloaded. Tap Install to continue."); private set
    private var launchChecked = false
    private var job: Job? = null
    val busy get() = state == AppUpdateState.CHECKING || state == AppUpdateState.DOWNLOADING

    fun onLaunch() { if (!launchChecked) { launchChecked = true; check(false) } }
    fun check(manual: Boolean = true) {
        if (busy) return
        state = AppUpdateState.CHECKING; showDialog = manual
        job = scope.launch {
            try {
                release = runInterruptible(Dispatchers.IO) { source.check() }
                state = if (release == null) AppUpdateState.CURRENT else AppUpdateState.AVAILABLE
                showDialog = manual || release != null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = AppUpdateState.CHECK_FAILED; showDialog = manual }
        }
    }
    fun review() { if (!busy && release != null) showDialog = true }
    fun download() {
        val update = release ?: return
        if (busy) return
        state = AppUpdateState.DOWNLOADING; showDialog = true; progress = 0; readyFile = null
        job = scope.launch {
            try {
                readyFile = runInterruptible(Dispatchers.IO) { source.download(update) { percent ->
                    scope.launch { if (state == AppUpdateState.DOWNLOADING) progress = percent }
                } }
                installMessage = "Update downloaded. Tap Install to continue."
                state = AppUpdateState.READY
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = AppUpdateState.DOWNLOAD_FAILED }
        }
    }
    fun installPermissionDenied() { installMessage = "Allow StreamPocket to install updates, then tap Install."; showDialog = true }
    fun installFailed() { installMessage = "Couldn't open the installer. Tap Install to try again."; showDialog = true }
    fun dismiss() { if (!busy) showDialog = false }
    fun cancelDownload() { if (state == AppUpdateState.DOWNLOADING) { job?.cancel(); state = AppUpdateState.AVAILABLE; showDialog = false } }
}
