package app.streampocket

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.io.FileNotFoundException
import java.util.UUID

/** WorkManager owns its records; keep per-item removals durable without pruning unrelated work. */
class DownloadHistory(context: Context) {
    private val preferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE)
    fun removed(): Set<String> = preferences.getStringSet("removed", emptySet()).orEmpty().toSet()
    fun hide(id: UUID) = synchronized(lock) {
        check(preferences.edit().putStringSet("removed", removed() + id.toString()).commit()) { "Could not save the download list." }
    }
    companion object { private val lock = Any() }
}

object DownloadRemoval {
    fun run(deleteFile: Boolean, uri: String?, delete: (String) -> Unit, removeEntry: () -> Unit): Boolean {
        var missing = false
        if (deleteFile && !uri.isNullOrBlank()) {
            try { delete(uri) } catch (_: FileNotFoundException) { missing = true }
        }
        // Do not hide the row if file deletion fails: the user can retry or choose to keep it.
        removeEntry()
        return missing
    }

    fun deleteFile(context: Context, value: String) {
        val uri = Uri.parse(value)
        check(uri.scheme == "content") { "This file cannot be deleted through Android storage. The download entry was kept." }
        fun exists(): Boolean = try {
            val listed = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() }
                ?: throw IOException("Cannot access this folder. The download entry was kept.")
            // A MediaStore row can outlive its file until Android updates the index.
            if (listed && uri.authority == "media") context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false else listed
        } catch (_: FileNotFoundException) { false }
        if (!exists()) throw FileNotFoundException("File not found")
        val deleted = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else context.contentResolver.delete(uri, null, null) > 0
        if (!deleted) {
            if (!exists()) throw FileNotFoundException("File not found")
            throw IOException("Could not delete the file. Check folder access and try again.")
        }
    }
}

const val DONATION_URL = "https://ko-fi.com/gatofile"
