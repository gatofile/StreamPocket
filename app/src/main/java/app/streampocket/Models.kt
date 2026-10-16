package app.streampocket

import org.json.JSONObject
import java.net.URI
import java.text.Normalizer

enum class Format(val mime: String, val qualities: List<Int>) {
    MP3("audio/mpeg", listOf(64, 96, 128, 192, 256, 320)),
    MP4("video/mp4", listOf(144, 240, 360, 480, 720, 1080, 1440, 2160));
    fun label(quality: Int) = if (this == MP3) "$quality kbps" else "${quality}p"
}

data class Media(
    val id: String, val url: String, val title: String, val channel: String = "",
    val duration: Int = 0, val thumbnail: String = "", val track: String = "",
    val artist: String = "", val album: String = "", val albumArtist: String = "",
    val year: String = "", val genre: String = "", val trackNumber: String = "",
    val heights: List<Int> = emptyList(), val description: String = ""
) {
    val music: Boolean get() = track.isNotBlank() && artist.isNotBlank()
    val caption: String get() = listOf(channel, if (duration > 0) "%d:%02d".format(duration / 60, duration % 60) else "").filter(String::isNotBlank).joinToString(" · ")
    fun json() = JSONObject().put("id", id).put("url", url).put("title", title).put("channel", channel)
        .put("duration", duration).put("thumbnail", thumbnail).put("track", track).put("artist", artist)
        .put("album", album).put("albumArtist", albumArtist).put("year", year).put("genre", genre)
        .put("trackNumber", trackNumber).put("description", description)
        .put("heights", org.json.JSONArray(heights))
    companion object {
        fun read(j: JSONObject) = Media(j.getString("id"), j.getString("url"), j.getString("title"),
            j.optString("channel"), j.optInt("duration"), j.optString("thumbnail"), j.optString("track"),
            j.optString("artist"), j.optString("album"), j.optString("albumArtist"), j.optString("year"),
            j.optString("genre"), j.optString("trackNumber"),
            j.optJSONArray("heights")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(), j.optString("description"))
    }
}

data class Tags(
    val title: String, val artist: String, val album: String, val albumArtist: String,
    val year: String, val genre: String, val track: String, val cover: String
) {
    fun json() = JSONObject().put("title", title).put("artist", artist).put("album", album)
        .put("albumArtist", albumArtist).put("year", year).put("genre", genre).put("track", track).put("cover", cover)
    companion object {
        fun from(m: Media) = Tags(m.track.ifBlank { m.title }, m.artist, m.album, m.albumArtist, m.year, m.genre, m.trackNumber, m.thumbnail)
        fun read(j: JSONObject) = Tags(j.optString("title"), j.optString("artist"), j.optString("album"), j.optString("albumArtist"), j.optString("year"), j.optString("genre"), j.optString("track"), j.optString("cover"))
        /** Fill richer fields without replacing anything the user already changed. */
        fun enrich(current: Tags, previous: Tags, next: Tags) = Tags(
            if (current.title == previous.title) next.title else current.title,
            if (current.artist == previous.artist) next.artist else current.artist,
            if (current.album == previous.album) next.album else current.album,
            if (current.albumArtist == previous.albumArtist) next.albumArtist else current.albumArtist,
            if (current.year == previous.year) next.year else current.year,
            if (current.genre == previous.genre) next.genre else current.genre,
            if (current.track == previous.track) next.track else current.track,
            if (current.cover == previous.cover) next.cover else current.cover)
    }
}

data class DownloadSpec(val media: Media, val tags: Tags, val format: Format, val quality: Int, val folder: String) {
    fun json() = JSONObject().put("media", media.json()).put("tags", tags.json()).put("format", format.name).put("quality", quality).put("folder", folder)
    companion object {
        fun read(j: JSONObject) = DownloadSpec(Media.read(j.getJSONObject("media")), Tags.read(j.getJSONObject("tags")), Format.valueOf(j.getString("format")), j.getInt("quality"), j.getString("folder"))
    }
}

object Input {
    // Accept known YouTube hosts only. Playlist parameters never turn one item into a batch.
    fun videoUrl(text: String): String? {
        val value = text.trim().let { if (it.startsWith("www.") || it.startsWith("youtu.be/") || it.startsWith("youtube.com/") || it.startsWith("music.youtube.com/") || it.startsWith("m.youtube.com/")) "https://$it" else it }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        val id = when (host) {
            "youtu.be" -> uri.path.trim('/').substringBefore('/')
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> {
                val path = uri.path.orEmpty()
                if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) path.split('/').getOrNull(2)
                else uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://www.youtube.com/watch?v=$it" }
    }
    fun isUrl(text: String) = text.trim().let { it.contains("://") || it.startsWith("www.") || it.startsWith("youtu.be/") || it.startsWith("youtube.com/") || it.startsWith("music.youtube.com/") || it.startsWith("m.youtube.com/") }
    fun fileName(title: String): String = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim().trim('.').take(120).ifBlank { "download" }
}

object MusicMatcher {
    private fun normalized(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun cleanTitle(s: String) = normalized(s.replace(Regex("(?i)\\s*[\\[(](official.*?|music video|audio|lyrics?|visuali[sz]er)[\\])]"), ""))
    private fun similar(a: String, b: String): Boolean {
        val x = cleanTitle(a).split(' ').filter(String::isNotBlank).toSet()
        val y = cleanTitle(b).split(' ').filter(String::isNotBlank).toSet()
        return x.isNotEmpty() && y.isNotEmpty() && x.intersect(y).size.toDouble() / maxOf(x.size, y.size) >= 0.85
    }
    fun choose(original: Media, candidates: List<Media>): Media? {
        if (!original.music) return null
        return candidates.filter { c ->
            c.id != original.id && c.music && c.album.isNotBlank() &&
                (c.channel.endsWith(" - Topic", true) || c.description.contains("Provided to YouTube by", true)) &&
                similar(original.track, c.track) && similar(original.artist, c.artist) &&
                c.duration > 0 && original.duration > 0 &&
                c.duration <= original.duration + 5 && c.duration >= original.duration * 0.6 &&
                listOf("live", "remix", "cover", "sped", "slowed", "instrumental").all {
                    val word = Regex("\\b$it\\b", RegexOption.IGNORE_CASE)
                    word.containsMatchIn(original.track) == word.containsMatchIn(c.track)
                }
        }.minByOrNull { it.duration }
    }
}

object DownloadOptions {
    fun forFormat(format: Format, quality: Int): List<Pair<String, String?>> {
        require(quality in format.qualities)
        return if (format == Format.MP3) listOf("-f" to "bestaudio/best", "-x" to null,
            "--audio-format" to "mp3", "--audio-quality" to "${quality}K")
        else listOf("-f" to "bv[height<=$quality][ext=mp4][vcodec^=avc1]+ba[ext=m4a]/b[height<=$quality][ext=mp4]/bv[height<=$quality][ext=mp4]+ba[ext=m4a]",
            "--merge-output-format" to "mp4", "--remux-video" to "mp4")
    }
}

object Metadata {
    fun apply(raw: JSONObject, tags: Tags, source: String) {
        mapOf("title" to tags.title, "track" to tags.title, "artist" to tags.artist,
            "album" to tags.album, "album_artist" to tags.albumArtist,
            "genre" to tags.genre).forEach { (key, value) -> raw.put(key, value) }
        raw.remove("release_year")
        raw.remove("track_number")
        tags.year.toIntOrNull()?.let { raw.put("release_year", it) }
        tags.track.substringBefore('/').toIntOrNull()?.let { raw.put("track_number", it) }
        mapOf("title" to tags.title, "artist" to tags.artist, "album" to tags.album,
            "album_artist" to tags.albumArtist, "date" to tags.year, "genre" to tags.genre,
            "track" to tags.track, "purl" to source, "comment" to source).forEach { (key, value) -> raw.put("meta_$key", value) }
        raw.put("thumbnail", tags.cover)
        raw.put("thumbnails", if (tags.cover.isBlank()) org.json.JSONArray() else org.json.JSONArray().put(JSONObject().put("url", tags.cover).put("id", "cover")))
        // yt-dlp can otherwise re-extract a failed info file and silently discard the user's edits.
        // URLs have just been refreshed. Report failures for manual retry, keeping source attribution in meta_purl.
        raw.remove("webpage_url")
        raw.remove("original_url")
    }
}
