package app.streampocket

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Review(val original: Media, val selected: Media, val tags: Tags, val format: Format, val quality: Int, val folder: String, val matched: Media? = null, val message: String = "", val editTags: Boolean = false, val metadataLoading: Boolean = false, val matching: Boolean = false, val sourceChosen: Boolean = false)

enum class EngineUpdateState { CURRENT, AVAILABLE, INSTALLING, CHECK_FAILED, UPDATE_FAILED }

class AppViewModel(application: Application, private val repository: MediaSource, appUpdates: AppUpdateSource = AppUpdateManager(application)) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, MediaRepository(application))
    val appUpdate = AppUpdateController(appUpdates, viewModelScope)
    private val preferences = Preferences(application)
    private val manager = WorkManager.getInstance(application)
    private val history = DownloadHistory(application)
    private val removedDownloads = MutableStateFlow(history.removed())
    private var reviewJob: Job? = null
    var settings by mutableStateOf(preferences.read()); private set
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Media>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var busyMessage by mutableStateOf("Finding your video…"); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var review by mutableStateOf<Review?>(null)
    var tab by mutableIntStateOf(0)
    var downloadFormat by mutableStateOf(Format.MP3)
    var engineVersion by mutableStateOf(repository.engineVersion()); private set
    var updatingEngine by mutableStateOf(false); private set
    var engineUpdateState by mutableStateOf<EngineUpdateState?>(null); private set
    var showEngineUpdateDialog by mutableStateOf(false); private set
    val engineUpdateAvailable get() = pendingEngineRelease != null
    private var pendingEngineRelease by mutableStateOf<EngineRelease?>(null)
    var removingDownloads by mutableStateOf<Set<UUID>>(emptySet()); private set
    val downloads = combine(manager.getWorkInfosByTagFlow("download"), removedDownloads) { jobs, removed ->
        jobs.filterNot { it.id.toString() in removed }
    }

    fun settings(value: Settings) { settings = value; preferences.write(value) }
    fun search() {
        if (busy || query.isBlank()) return
        val input = query.trim()
        val url = Input.videoUrl(input)
        if (Input.isUrl(input) && url == null) { error = "Paste a valid YouTube video link, or search by name."; return }
        if (url != null) {
            select(Media(url.substringAfter("v="), url, "YouTube video"))
        } else work {
            val found = repository.search(input)
            withContext(Dispatchers.Main) { results = found; engineVersion = repository.engineVersion(); if (found.isEmpty()) error = "No videos found. Try a different name." }
        }
    }
    fun select(item: Media) {
        if (busy) return
        reviewJob?.cancel()
        val preferSong = settings.preferSong
        // This happens synchronously; opening the review never waits on YouTube.
        review = Review(item, item, Tags.from(item), Format.MP3, settings.audioQuality, settings.audioFolder,
            message = "Loading metadata… You can choose your format and folder now.", metadataLoading = true)
        reviewJob = viewModelScope.launch {
            try {
                val media = runInterruptible(Dispatchers.IO) { repository.inspect(item.url) }
                val r = review?.takeIf { it.original.id == item.id } ?: return@launch
                val matching = preferSong && media.music && !media.channel.endsWith(" - Topic", true)
                review = r.copy(original = media, selected = media,
                    tags = Tags.enrich(r.tags, Tags.from(item), Tags.from(media)), metadataLoading = false, matching = matching,
                    message = if (matching) "Looking for a song release. You can download the selected version now." else "Review the details before downloading.")
                engineVersion = repository.engineVersion()
                if (matching) {
                    val match = runInterruptible(Dispatchers.IO) { repository.songVersion(media) }
                    review?.takeIf { it.original.id == item.id }?.let { current ->
                        val useMatch = match != null && !current.sourceChosen
                        review = current.copy(selected = if (useMatch) match!! else current.selected, matched = match, matching = false,
                            tags = if (useMatch) Tags.enrich(current.tags, Tags.from(current.selected), Tags.from(match!!)) else current.tags,
                            message = if (useMatch) "Matched an official song release. This version is selected below." else if (match != null) "A song release is available below." else "No confident song match found. The original video is selected.")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                review?.takeIf { it.original.id == item.id }?.let { current ->
                    review = current.copy(metadataLoading = false, matching = false, message = if (current.metadataLoading) "Metadata could not load. You can edit the tags and retry the download." else "Song lookup was unavailable. The selected video is ready.")
                    if (current.metadataLoading) error = downloadError(e)
                }
            }
        }
    }
    fun dismissReview() { reviewJob?.cancel(); review = null }
    fun format(f: Format) { review?.let { review = it.copy(format = f, quality = settings.quality(f), folder = settings.folder(f)) } }
    fun useOriginal(original: Boolean) { review?.let { r -> val m = if (original) r.original else r.matched ?: r.original; review = r.copy(selected = m, tags = Tags.enrich(r.tags, Tags.from(r.selected), Tags.from(m)), sourceChosen = true) } }
    fun enqueue() {
        val r = review ?: return
        if (r.metadataLoading) return
        if (r.tags.title.isBlank()) { error = "Enter a title for this download."; return }
        if (r.tags.cover.isNotBlank() && !runCatching { java.net.URI(r.tags.cover).let { it.scheme == "https" && it.host != null } }.getOrDefault(false)) {
            error = "Use an HTTPS link for the cover image, or clear the cover field."; return
        }
        val spec = DownloadSpec(r.selected, r.tags, r.format, r.quality, r.folder)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("download").addTag("name:${r.tags.title.take(120)}").addTag("format:${r.format.name}")
            .build()
        val file = File(getApplication<Application>().filesDir, "jobs/${request.id}.json")
        try {
            file.parentFile?.mkdirs(); file.writeText(spec.json().toString())
            manager.enqueue(request)
            dismissReview(); downloadFormat = r.format; tab = 1
        } catch (e: Exception) { error = "Could not queue the download: ${e.message}" }
    }
    fun cancel(id: UUID) { manager.cancelWorkById(id) }
    fun removeDownload(job: WorkInfo, deleteFile: Boolean) {
        if (job.id in removingDownloads) return
        removingDownloads = removingDownloads + job.id
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    removeSavedDownload(job, deleteFile)
                }
                removedDownloads.value = removedDownloads.value + removed.first
                if (removed.second) notice = "File not found"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Could not remove this download. ${e.message.orEmpty()}" }
            finally { removingDownloads = removingDownloads - job.id }
        }
    }
    private fun removeSavedDownload(job: WorkInfo, deleteFile: Boolean): Pair<Set<String>, Boolean> {
        var latest = manager.getWorkInfoById(job.id).get() ?: job
        if (!latest.state.isFinished) {
            manager.cancelWorkById(job.id).result.get()
            latest = manager.getWorkInfoById(job.id).get() ?: latest
        }
        val missing = DownloadRemoval.run(deleteFile, latest.outputData.getString("uri") ?: job.outputData.getString("uri"),
            delete = { DownloadRemoval.deleteFile(getApplication(), it) }, removeEntry = { history.hide(job.id) })
        File(getApplication<Application>().filesDir, "jobs/${job.id}.json").delete()
        return history.removed() to missing
    }
    fun removeAllDownloads(jobs: List<WorkInfo>, deleteFiles: Boolean) {
        if (jobs.isEmpty() || removingDownloads.isNotEmpty()) return
        val selected = jobs.distinctBy { it.id }
        val ids = selected.map { it.id }.toSet()
        removingDownloads = ids
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var missing = 0
                    var failed = 0
                    selected.forEach { job ->
                        currentCoroutineContext().ensureActive()
                        try { if (removeSavedDownload(job, deleteFiles).second) missing++ }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed++ }
                    }
                    Triple(history.removed(), missing, failed)
                }
                removedDownloads.value = removedDownloads.value + result.first
                if (result.second > 0) notice = "File not found"
                else if (result.third == 0) notice = "Downloads removed"
                if (result.third > 0) error = "Couldn't remove ${result.third} ${if (result.third == 1) "download" else "downloads"}. Check folder access and try again."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Could not remove the downloads. ${e.message.orEmpty()}" }
            finally { removingDownloads = removingDownloads - ids }
        }
    }
    fun showInFolder(job: WorkInfo, open: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            try {
                val folder = withContext(Dispatchers.IO) { DownloadFolders.location(getApplication(), job) }
                open(folder)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Couldn't open folder" }
        }
    }
    fun retry(id: UUID) {
        val file = File(getApplication<Application>().filesDir, "jobs/$id.json")
        runCatching { DownloadSpec.read(JSONObject(file.readText())) }.onSuccess { spec ->
            review = Review(spec.media, spec.media, spec.tags, spec.format, spec.quality, spec.folder, message = "Review the destination and retry.")
        }.onFailure { error = "The saved download details are unavailable." }
    }
    fun checkEngineUpdate(showDialog: Boolean = true) {
        if (updatingEngine) return
        updatingEngine = true
        showEngineUpdateDialog = showDialog
        engineUpdateState = null
        pendingEngineRelease = null
        viewModelScope.launch {
            try {
                pendingEngineRelease = runInterruptible(Dispatchers.IO) { repository.checkEngineUpdate() }
                engineUpdateState = if (pendingEngineRelease == null) EngineUpdateState.CURRENT else EngineUpdateState.AVAILABLE
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { engineUpdateState = EngineUpdateState.CHECK_FAILED }
            finally { engineVersion = repository.engineVersion(); updatingEngine = false }
        }
    }
    fun updateEngine() {
        val release = pendingEngineRelease ?: return
        if (updatingEngine) return
        updatingEngine = true
        showEngineUpdateDialog = true
        engineUpdateState = EngineUpdateState.INSTALLING
        viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) { repository.updateEngine(release) }
                pendingEngineRelease = null
                engineUpdateState = EngineUpdateState.CURRENT
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { engineUpdateState = EngineUpdateState.UPDATE_FAILED }
            finally { engineVersion = repository.engineVersion(); updatingEngine = false }
        }
    }
    fun dismissEngineUpdate() {
        if (updatingEngine) return
        showEngineUpdateDialog = false
    }
    fun reviewEngineUpdate() {
        if (engineUpdateAvailable && !updatingEngine) showEngineUpdateDialog = true
    }
    private fun work(task: suspend () -> Unit) {
        busy = true; busyMessage = "Finding your video…"; error = null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { task() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = downloadError(e) }
            finally { busy = false }
        }
    }
}

fun downloadError(e: Exception): String {
    val message = e.message.orEmpty()
    val errors = message.lineSequence().filter { it.contains("ERROR:") }.toList()
    return (if (errors.isNotEmpty()) errors.joinToString("\n") else message).takeLast(1500).ifBlank { "Could not reach YouTube. Try again." }
}
