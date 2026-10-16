package app.streampocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class DownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val processId = "download-$id"
    override fun doWork(): Result {
        val job = File(applicationContext.filesDir, "jobs/$id.json")
        var lastProgress = 0L
        return try {
            val spec = DownloadSpec.read(JSONObject(job.readText()))
            setForegroundAsync(notification(spec.tags.title, "Preparing download")).get()
            stage("Preparing", 0)
            val dir = File(applicationContext.filesDir, "staging/$id").apply { mkdirs() }
            val repository = MediaRepository(applicationContext)
            val tags = spec.tags
            val info = File(dir, "info.json")
            StreamRecovery.run(prepare = { fresh ->
                val raw = repository.raw(spec.media.url, forceRefresh = fresh)
                check(!raw.optBoolean("is_live")) { "This video is live. Try again after the broadcast has finished." }
                check(!isStopped) { "Download cancelled" }
                Metadata.apply(raw, tags, spec.media.url)
                info.writeText(raw.toString())
            }, download = {
            val request = YoutubeDLRequest(emptyList<String>()).apply {
                addOption("--load-info-json", info.absolutePath)
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--newline")
                addOption("--socket-timeout", "25")
                addOption("--retries", "3")
                addOption("--embed-metadata")
                if (tags.cover.isNotBlank()) { addOption("--embed-thumbnail"); addOption("--convert-thumbnails", "jpg") }
                addOption("-o", File(dir, "media.%(ext)s").absolutePath)
                DownloadOptions.forFormat(spec.format, spec.quality).forEach { (key, value) -> if (value == null) addOption(key) else addOption(key, value) }
            }
            repository.execute(request, processId, callback = { progress, _, _ ->
                if (isStopped) { YoutubeDL.destroyProcessById(processId); throw IOException("Download cancelled") }
                if (System.currentTimeMillis() - lastProgress > 600) {
                    lastProgress = System.currentTimeMillis()
                    stage(if (progress >= 100) "Converting & tagging" else "Downloading", progress.toInt().coerceIn(0, 100))
                }
            })
            }, onRefresh = { stage("Refreshing stream after HTTP 403", 0) }, cancelled = { isStopped })
            check(!isStopped) { "Download cancelled" }
            val file = File(dir, "media.${spec.format.name.lowercase()}")
            check(file.exists() && file.length() > 0) { "Conversion did not produce a ${spec.format.name} file." }
            stage("Saving to your folder", 100)
            val saved = publish(file, spec)
            dir.deleteRecursively()
            Result.success(workDataOf("uri" to saved.toString(), "name" to tags.title, "mime" to spec.format.mime, "folder" to spec.folder))
        } catch (e: Exception) {
            val detail = downloadError(e)
            Result.failure(workDataOf("error" to detail))
        } finally {
            // This directory belongs only to this job. Never leave a failed conversion consuming storage.
            File(applicationContext.filesDir, "staging/$id").deleteRecursively()
        }
    }
    private fun stage(name: String, percent: Int) {
        setProgressAsync(workDataOf("stage" to name, "percent" to percent))
    }
    private fun notification(title: String, detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
        return ForegroundInfo(id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    private fun publish(file: File, spec: DownloadSpec): Uri {
        val resolver = applicationContext.contentResolver
        val name = Input.fileName(spec.tags.title) + "." + spec.format.name.lowercase()
        val uri: Uri
        val mediaStore = spec.folder.isBlank()
        if (mediaStore) {
            val audio = spec.format == Format.MP3
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, spec.format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (audio) "Music/StreamPocket" else "Movies/StreamPocket")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create a file in the default folder.")
        } else {
            val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(spec.folder))
            check(folder?.canWrite() == true) { "Folder access has expired. Choose the folder again and retry." }
            uri = folder!!.createFile(spec.format.mime, name)?.uri ?: throw IOException("Could not create a file in the selected folder.")
        }
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not write to the selected folder.")
            if (isStopped) throw IOException("Download cancelled")
            if (mediaStore) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
    override fun onStopped() { YoutubeDL.destroyProcessById(processId); super.onStopped() }
}
