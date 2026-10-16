package app.streampocket

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class Settings(val audioQuality: Int = 192, val videoQuality: Int = 1080, val audioFolder: String = "", val videoFolder: String = "", val preferSong: Boolean = true) {
    fun quality(f: Format) = if (f == Format.MP3) audioQuality else videoQuality
    fun folder(f: Format) = if (f == Format.MP3) audioFolder else videoFolder
}
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    fun read() = Settings(prefs.getInt("audioQuality", 192), prefs.getInt("videoQuality", 1080), prefs.getString("audioFolder", "").orEmpty(), prefs.getString("videoFolder", "").orEmpty(), prefs.getBoolean("preferSong", true))
    fun write(s: Settings) = prefs.edit().putInt("audioQuality", s.audioQuality).putInt("videoQuality", s.videoQuality)
        .putString("audioFolder", s.audioFolder).putString("videoFolder", s.videoFolder).putBoolean("preferSong", s.preferSong).apply()
}
fun folderLabel(context: Context, uri: String, f: Format): String {
    if (uri.isBlank()) return if (f == Format.MP3) "Music / StreamPocket" else "Movies / StreamPocket"
    return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull() ?: "Selected folder"
}
