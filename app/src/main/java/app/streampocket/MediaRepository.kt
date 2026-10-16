package app.streampocket

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

interface MediaSource {
    fun inspect(url: String): Media
    fun search(query: String, count: Int = 10): List<Media>
    fun songVersion(original: Media): Media?
    fun checkEngineUpdate(): EngineRelease?
    fun updateEngine(release: EngineRelease)
    fun engineVersion(): String
}

class MediaRepository(context: Context) : MediaSource {
    private val engine = EngineManager(context.applicationContext)
    fun initialize() = engine.initialize()
    private fun base(url: String) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--socket-timeout", "25")
        addOption("--retries", "3")
        addOption("--no-warnings")
    }
    fun raw(url: String, forceRefresh: Boolean = false): JSONObject {
        require(Input.videoUrl(url) != null) { "Enter a YouTube video URL." }
        if (forceRefresh) cached.remove(url)
        cached[url]?.takeIf { System.currentTimeMillis() - it.first < 120000 }?.let { return JSONObject(it.second) }
        val request = base(url).addOption("--skip-download").addOption("--dump-single-json")
        val result = engine.execute(request).out
        val parsed = JSONObject(result)
        if (cached.size > 32) cached.clear()
        cached[url] = System.currentTimeMillis() to result
        return parsed
    }
    override fun inspect(url: String) = parse(raw(url))
    override fun search(query: String, count: Int): List<Media> {
        require(query.trim().isNotBlank())
        val request = base("ytsearch$count:${query.trim().take(200)}")
            .addOption("--flat-playlist").addOption("--skip-download").addOption("--dump-single-json")
        val json = JSONObject(engine.execute(request).out)
        return json.optJSONArray("entries")?.let { entries ->
            (0 until entries.length()).mapNotNull { i -> entries.optJSONObject(i)?.let(::parse) }
        }.orEmpty()
    }
    override fun songVersion(original: Media): Media? {
        if (!original.music) return null
        if (original.channel.endsWith(" - Topic", true)) return null
        val hits = search("${original.artist} ${original.track} official audio", 6)
        val prioritized = hits.filter { it.id != original.id }.sortedByDescending { it.channel.endsWith(" - Topic", true) }
        for (hit in prioritized.take(3)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val candidate = try { inspect(hit.url) } catch (e: InterruptedException) { throw e } catch (_: Exception) { continue }
            MusicMatcher.choose(original, listOf(candidate))?.let { return it }
        }
        return null
    }
    override fun checkEngineUpdate() = engine.checkUpdate()
    override fun updateEngine(release: EngineRelease) { engine.update(release); cached.clear() }
    override fun engineVersion() = engine.installedVersion()
    fun execute(request: YoutubeDLRequest, processId: String, callback: (Float, Long, String) -> Unit) = engine.execute(request, processId, callback)
    companion object {
        private val cached = ConcurrentHashMap<String, Pair<Long, String>>()
        fun parse(j: JSONObject): Media {
            fun s(key: String) = if (j.isNull(key)) "" else j.optString(key).takeUnless { it == "NA" }.orEmpty()
            val id = s("id")
            val channel = s("channel").ifBlank { s("uploader") }
            val title = s("title")
            val category = j.optJSONArray("categories")?.toString().orEmpty()
            val isMusic = category.contains("Music", true) || channel.endsWith(" - Topic", true)
            val split = title.split(" - ", limit = 2)
            val artist = s("artist").ifBlank { s("creator") }.ifBlank {
                if (isMusic && split.size == 2) split[0] else ""
            }
            val track = s("track").ifBlank { if (isMusic && split.size == 2) split[1].replace(Regex("(?i)\\s*[\\[(](official.*?|music video|audio|lyrics?)[\\])]"), "").trim() else "" }
            val thumbnails = j.optJSONArray("thumbnails")
            val thumbnail = s("thumbnail").ifBlank { thumbnails?.let { if (it.length() > 0) it.optJSONObject(it.length()-1)?.optString("url") else null }.orEmpty() }
            val formats = j.optJSONArray("formats")
            val heights = formats?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("height")?.takeIf { h -> h > 0 } }.distinct().sorted() }.orEmpty()
            return Media(id, "https://www.youtube.com/watch?v=$id", title, channel, j.optDouble("duration", 0.0).toInt(),
                thumbnail, track, artist, s("album"), s("album_artist"), s("release_year"), s("genre"), s("track_number"), heights, s("description"))
        }
    }
}
