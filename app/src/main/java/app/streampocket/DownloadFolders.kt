package app.streampocket

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.WorkInfo
import org.json.JSONObject
import java.io.File

object DownloadFolders {
    fun location(context: Context, job: WorkInfo): Uri {
        val file = File(context.filesDir, "jobs/${job.id}.json")
        val spec = runCatching { DownloadSpec.read(JSONObject(file.readText())) }.getOrNull()
        val folder = job.outputData.getString("folder") ?: spec?.folder.orEmpty()
        val format = spec?.format ?: if (job.tags.contains("format:MP4")) Format.MP4 else Format.MP3
        if (folder.isNotBlank()) return documentUri(folder)
        val saved = job.outputData.getString("uri")?.let(Uri::parse)
        if (saved != null && DocumentsContract.isTreeUri(saved)) return documentUri(saved.toString())
        return defaultFolder(format)
    }
    fun defaultFolder(format: Format): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
        if (format == Format.MP3) "primary:Music/StreamPocket" else "primary:Movies/StreamPocket")

    fun documentUri(folder: String): Uri {
        val uri = Uri.parse(folder)
        return if (DocumentsContract.isTreeUri(uri)) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
    }
    fun viewIntent(folder: Uri): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            if (DocumentsContract.isTreeUri(folder)) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    fun browserIntent(folder: Uri): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*").putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context, folder: Uri) {
        try { context.startActivity(viewIntent(folder)) }
        catch (_: ActivityNotFoundException) { context.startActivity(browserIntent(folder)) }
        catch (_: SecurityException) { context.startActivity(browserIntent(folder)) }
    }
}
