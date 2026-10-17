package app.streampocket

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import java.io.File

private val Accent = Color(0xFFB2C9E8)
private val Background = Color(0xFF121315)
private val Panel = Color(0xFF1C1E21)
private val Muted = Color(0xFFA5A8AE)
private val Palette = darkColorScheme(primary = Accent, onPrimary = Color(0xFF17263B), background = Background,
    surface = Panel, surfaceContainer = Panel, surfaceContainerHigh = Color(0xFF272A2F),
    onSurface = Color(0xFFE4E5E8), onBackground = Color(0xFFE4E5E8), secondary = Accent,
    secondaryContainer = Color(0xFF2E3B4D), onSecondaryContainer = Accent, outline = Color(0xFF51555B))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
        setContent { StreamPocketTheme { StreamPocket(shared) } }
    }
}

@Composable
internal fun StreamPocketTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = Palette, content = content) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPocket(shared: String = "", vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    AppUpdateDialogs(vm.appUpdate)
    val downloads by vm.downloads.collectAsStateWithLifecycle(emptyList())
    val snackbar = remember { SnackbarHostState() }
    var folderCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onSuccess { folderCallback?.invoke(uri.toString()) }
            .onFailure { vm.notice = "Couldn't access this folder" }
        folderCallback = null
    }
    val pick: ((String) -> Unit) -> Unit = { callback -> folderCallback = callback; folderPicker.launch(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(shared) { if (shared.isNotBlank()) vm.query = Regex("https?://[^\\s]+").find(shared)?.value ?: shared }
    LaunchedEffect(vm.notice) {
        vm.notice?.let { message -> snackbar.showSnackbar(message); if (vm.notice == message) vm.notice = null }
    }
    Scaffold(containerColor = Background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("StreamPocket", fontSize = 20.sp, fontWeight = FontWeight.Medium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)) },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                listOf("Search" to Icons.Rounded.Search, "Downloads" to Icons.Rounded.Download, "Settings" to Icons.Rounded.Settings).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = vm.tab == i, onClick = { vm.tab = i }, icon = { Icon(icon, null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF2E3B4D), selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (vm.tab) { 0 -> SearchScreen(vm); 1 -> Downloads(vm, downloads); else -> SettingsScreen(vm, pick) }
        }
    }
    vm.review?.let { r -> ReviewScreen(vm, r, pick) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.enqueue()
    } }
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("StreamPocket") },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ vm.error = null }) { Text("OK") } }) }
    vm.engineUpdateState?.takeIf { vm.showEngineUpdateDialog }?.let { state ->
        AlertDialog(onDismissRequest = vm::dismissEngineUpdate, title = { Text("Download engine") },
            text = {
                when (state) {
                    EngineUpdateState.CURRENT -> Text("The download engine is up to date.")
                    EngineUpdateState.AVAILABLE -> Text("An update is available.")
                    EngineUpdateState.INSTALLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp)); Text("Updating…")
                    }
                    EngineUpdateState.CHECK_FAILED -> Text("Couldn't check for updates. Try again.")
                    EngineUpdateState.UPDATE_FAILED -> Text("Couldn't update the download engine. Try again.")
                }
            },
            confirmButton = {
                when (state) {
                    EngineUpdateState.CURRENT -> TextButton(vm::dismissEngineUpdate) { Text("OK") }
                    EngineUpdateState.AVAILABLE -> TextButton(vm::updateEngine) { Text("Update") }
                    EngineUpdateState.CHECK_FAILED -> TextButton({ vm.checkEngineUpdate() }) { Text("Retry") }
                    EngineUpdateState.UPDATE_FAILED -> TextButton(vm::updateEngine) { Text("Retry") }
                    EngineUpdateState.INSTALLING -> Unit
                }
            },
            dismissButton = {
                if (state != EngineUpdateState.CURRENT && state != EngineUpdateState.INSTALLING)
                    TextButton(vm::dismissEngineUpdate) { Text("Cancel") }
            })
    }
}

@Composable
private fun SearchScreen(vm: AppViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(vm.query, { vm.query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Video name or YouTube link", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.search() }))
            Spacer(Modifier.height(10.dp))
            Button(vm::search, enabled = !vm.busy && vm.query.isNotBlank(), modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(10.dp)) {
                if (vm.busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (vm.busy) "Searching…" else "Search videos")
            }
        }
        if (vm.results.isEmpty()) item {
            Text("Search by name or paste a YouTube link.", Modifier.fillMaxWidth().padding(top = 32.dp), color = Muted, fontSize = 14.sp)
        } else {
            item { Text("${vm.results.size} results", color = Muted, fontSize = 13.sp) }
            items(vm.results, key = { it.id }) { media ->
                Surface(onClick = { vm.select(media) }, enabled = !vm.busy, color = Background) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(media.thumbnail, Modifier.size(64.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                            Spacer(Modifier.height(5.dp)); Text(media.caption, color = Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        Icon(Icons.Rounded.ChevronRight, "Download options", tint = Muted, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = Panel)
            }
        }
    }
}

@Composable
private fun Cover(url: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Panel), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Muted, modifier = Modifier.size(24.dp))
        if (url.isNotBlank()) AsyncImage(url, "Cover artwork", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun GroupLabel(text: String) { Text(text, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun QualityChoice(label: String, format: Format, selected: Int, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Box {
            OutlinedButton({ expanded = true }, shape = RoundedCornerShape(8.dp)) {
                Text(format.label(selected)); Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { expanded = false }) {
                format.qualities.forEach { q -> DropdownMenuItem(text = { Text(format.label(q)) }, onClick = { change(q); expanded = false },
                    trailingIcon = if (q == selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null) }
            }
        }
    }
}

@Composable
private fun FolderChoice(folder: String, format: Format, change: () -> Unit, reset: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.FolderOpen, null, tint = Muted, modifier = Modifier.size(22.dp))
        Text(folderLabel(context, folder, format), Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(change) { Text("Change") }
    }
    if (folder.isNotBlank()) TextButton(reset) { Text("Use default folder") }
}

@Composable
private fun ReviewScreen(vm: AppViewModel, r: Review, pick: ((String) -> Unit) -> Unit, download: () -> Unit) {
    Dialog(onDismissRequest = vm::dismissReview, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(vm::dismissReview) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("Download", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(r.tags.cover, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(r.selected.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(r.selected.caption, fontSize = 12.sp, color = Muted, maxLines = 1)
                        }
                    } }
                    if (r.metadataLoading || r.matching) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(if (r.metadataLoading) "Loading details…" else "Checking song version…", Modifier.padding(start = 10.dp), color = Muted, fontSize = 13.sp)
                        }
                    }
                    if (r.matched != null) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(r.selected.id == r.matched.id, { vm.useOriginal(false) }, label = { Text("Song version") })
                            FilterChip(r.selected.id == r.original.id, { vm.useOriginal(true) }, label = { Text("Original video") })
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Format", Modifier.weight(1f), fontSize = 15.sp)
                            Format.entries.forEach { format -> FilterChip(r.format == format, { vm.format(format) }, label = { Text(format.name) }) }
                        }
                        QualityChoice("Quality", r.format, r.quality) { vm.review = vm.review?.copy(quality = it) }
                    }
                    item {
                        HorizontalDivider(color = Panel)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Metadata", Modifier.weight(1f), fontSize = 15.sp)
                            TextButton({ vm.review = vm.review?.copy(editTags = !r.editTags) }) { Text(if (r.editTags) "Done" else "Edit metadata") }
                        }
                        if (r.editTags) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TagField("Title", r.tags.title) { vm.review = vm.review?.copy(tags = r.tags.copy(title = it)) }
                            TagField("Artist", r.tags.artist) { vm.review = vm.review?.copy(tags = r.tags.copy(artist = it)) }
                            TagField("Album", r.tags.album) { vm.review = vm.review?.copy(tags = r.tags.copy(album = it)) }
                            TagField("Album artist", r.tags.albumArtist) { vm.review = vm.review?.copy(tags = r.tags.copy(albumArtist = it)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) { TagField("Year", r.tags.year) { vm.review = vm.review?.copy(tags = r.tags.copy(year = it)) } }
                                Box(Modifier.weight(1f)) { TagField("Track", r.tags.track) { vm.review = vm.review?.copy(tags = r.tags.copy(track = it)) } }
                            }
                            TagField("Genre", r.tags.genre) { vm.review = vm.review?.copy(tags = r.tags.copy(genre = it)) }
                            TagField("Cover image URL", r.tags.cover) { vm.review = vm.review?.copy(tags = r.tags.copy(cover = it)) }
                        } else Text(listOf(r.tags.artist, r.tags.album).filter(String::isNotBlank).joinToString(" · ").ifBlank { "No artist or album" }, color = Muted, fontSize = 13.sp)
                    }
                    item {
                        HorizontalDivider(color = Panel); Spacer(Modifier.height(12.dp)); Text("Save to", fontSize = 15.sp)
                        FolderChoice(r.folder, r.format, { pick { vm.review = vm.review?.copy(folder = it) } }, { vm.review = vm.review?.copy(folder = "") })
                    }
                }
                Button(download, enabled = !r.metadataLoading, modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Download ${r.format.name} · ${r.format.label(r.quality)}")
                }
            }
        }
    }
}

@Composable
private fun TagField(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(8.dp))
}

@Composable
private fun SettingsScreen(vm: AppViewModel, pick: ((String) -> Unit) -> Unit) {
    val s = vm.settings
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.checkEngineUpdate(showDialog = false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AppUpdateSetting(vm.appUpdate); HorizontalDivider(color = Panel) }
        item { GroupLabel("Quality"); QualityChoice("MP3 audio", Format.MP3, s.audioQuality) { vm.settings(s.copy(audioQuality = it)) }; QualityChoice("MP4 video", Format.MP4, s.videoQuality) { vm.settings(s.copy(videoQuality = it)) } }
        item { HorizontalDivider(color = Panel); Spacer(Modifier.height(12.dp)); GroupLabel("Folders") }
        item { Text("MP3 audio", fontSize = 14.sp); FolderChoice(s.audioFolder, Format.MP3, { pick { vm.settings(vm.settings.copy(audioFolder = it)) } }, { vm.settings(s.copy(audioFolder = "")) }) }
        item { Text("MP4 video", fontSize = 14.sp); FolderChoice(s.videoFolder, Format.MP4, { pick { vm.settings(vm.settings.copy(videoFolder = it)) } }, { vm.settings(s.copy(videoFolder = "")) }) }
        item {
            HorizontalDivider(color = Panel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) { Text("Prefer song version", fontSize = 15.sp); Text("Use official audio when available.", fontSize = 12.sp, color = Muted) }
                Switch(s.preferSong, { vm.settings(s.copy(preferSong = it)) })
            }
        }
        item {
            HorizontalDivider(color = Panel)
            Row(Modifier.testTag("engine-update-setting"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text("Download engine", fontSize = 15.sp)
                    Text(vm.engineVersion, fontSize = 12.sp, color = Muted)
                    Text(when {
                        vm.updatingEngine -> if (vm.engineUpdateState == EngineUpdateState.INSTALLING) "Updating…" else "Checking for updates…"
                        vm.engineUpdateAvailable -> "Update available"
                        vm.engineUpdateState == EngineUpdateState.CURRENT -> "Up to date"
                        vm.engineUpdateState == EngineUpdateState.CHECK_FAILED -> "Couldn't check for updates"
                        else -> ""
                    }, fontSize = 12.sp, color = Muted)
                }
                when {
                    vm.updatingEngine -> CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    vm.engineUpdateAvailable -> TextButton(vm::reviewEngineUpdate) { Text("Update") }
                    else -> IconButton({ vm.checkEngineUpdate() }) { Icon(Icons.Rounded.Refresh, "Check for updates", tint = Accent) }
                }
            }
        }
        item { Button(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL))) }.onFailure { vm.notice = "Couldn't open Ko-fi" } },
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF83D5E9), contentColor = Color(0xFF12333C))) {
            Icon(Icons.Rounded.LocalCafe, null, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
            Text("Donate on Ko-fi", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
        } }
        item { Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp) }
    }
}

@Composable
internal fun SavedDownloadActions(open: () -> Unit, showFolder: () -> Unit) {
    Row {
        TextButton(open) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Open") }
        TextButton(showFolder) { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Show in folder") }
    }
}

@Composable
internal fun Downloads(vm: AppViewModel, jobs: List<WorkInfo>) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<WorkInfo?>(null) }
    val sorted = jobs.filter { it.tags.contains("format:${vm.downloadFormat.name}") }
        .sortedByDescending { File(context.filesDir, "jobs/${it.id}.json").lastModified() }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = vm.downloadFormat.ordinal, containerColor = Background, contentColor = Accent) {
            Format.entries.forEach { format ->
                Tab(selected = vm.downloadFormat == format, onClick = { vm.downloadFormat = format },
                    text = { Text(format.name) }, unselectedContentColor = Muted)
            }
        }
        key(vm.downloadFormat) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sorted.isEmpty()) item { Text("No ${vm.downloadFormat.name} downloads", Modifier.padding(top = 24.dp), color = Muted) }
                else item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (sorted.size == 1) "1 download" else "${sorted.size} downloads", color = Muted, fontSize = 12.sp); Text("Swipe left to delete", color = Muted, fontSize = 12.sp) } }
                items(sorted, key = { it.id }) { job ->
                    val title = job.tags.firstOrNull { it.startsWith("name:") }?.removePrefix("name:") ?: "Download"
                    val format = job.tags.firstOrNull { it.startsWith("format:") }?.removePrefix("format:") ?: ""
                    val failed = job.state == WorkInfo.State.FAILED
                    val status = when (job.state) {
                        WorkInfo.State.SUCCEEDED -> "Saved"
                        WorkInfo.State.FAILED -> "Failed"
                        WorkInfo.State.CANCELLED -> "Cancelled"
                        WorkInfo.State.RUNNING -> job.progress.getString("stage") ?: "Preparing"
                        else -> "Queued"
                    }
                    SwipeToRevealDelete(job.id.toString(), enabled = job.id !in vm.removingDownloads, onDelete = { pendingDelete = job }) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Panel) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("$format · ${if (job.id in vm.removingDownloads) "Removing…" else status}", color = if (failed) DeleteRed else Muted, fontSize = 12.sp)
                                if (job.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { job.progress.getInt("percent", 0) / 100f }, modifier = Modifier.fillMaxWidth())
                                if (failed) {
                                    var expanded by rememberSaveable(job.id.toString()) { mutableStateOf(false) }
                                    TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
                                    if (expanded) Text(job.outputData.getString("error").orEmpty(), color = Muted, fontSize = 12.sp)
                                }
                                if (!job.state.isFinished) TextButton({ vm.cancel(job.id) }) { Text("Cancel") }
                                if (failed || job.state == WorkInfo.State.CANCELLED) TextButton({ vm.retry(job.id) }) { Text("Retry") }
                                if (job.state == WorkInfo.State.SUCCEEDED) SavedDownloadActions(open = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.outputData.getString("uri")), job.outputData.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                        .onFailure { vm.notice = "File not found or no player available" }
                                }, showFolder = { vm.showInFolder(job) { DownloadFolders.open(context, it) } })
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { job -> DeleteDownloadDialog(
        title = job.tags.firstOrNull { it.startsWith("name:") }?.removePrefix("name:") ?: "Download",
        format = job.tags.firstOrNull { it.startsWith("format:") }?.removePrefix("format:") ?: "media",
        hasFile = !job.outputData.getString("uri").isNullOrBlank(), active = !job.state.isFinished,
        onDismiss = { pendingDelete = null }, onDelete = { deleteFile -> pendingDelete = null; vm.removeDownload(job, deleteFile) }) }
}
