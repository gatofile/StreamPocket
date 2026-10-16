package app.streampocket

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.ffmpeg.FFmpeg
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class EngineRelease(val version: String, val url: String, val sha256: String) {
    init {
        require(version.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}(\\.\\d+)?")))
        require(url == "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/$version/yt-dlp")
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
    }
}

class EngineManager(private val context: Context) {
    private val executable get() = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
    fun installedVersion(): String = EngineFiles.version(executable) ?: BUNDLED_VERSION

    fun initialize() {
        if (prepared) return
        lock.write {
            if (prepared) return@write
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
            if (EngineFiles.olderThan(EngineFiles.version(executable), BUNDLED_VERSION)) {
                val candidate = File.createTempFile("yt-dlp-bundled-", ".tmp", executable.parentFile)
                try {
                    context.assets.open("yt-dlp").use { input -> candidate.outputStream().use { input.copyTo(it) } }
                    EngineFiles.install(candidate, executable, BUNDLED_VERSION, BUNDLED_SHA256, ::runningVersion)
                } finally { candidate.delete() }
            }
            prepared = true
        }
    }

    fun execute(request: YoutubeDLRequest, processId: String? = null, callback: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse {
        initialize()
        // An update must not replace Python modules while another job is importing them.
        return lock.read { YoutubeDL.execute(request, processId, false, callback) }
    }

    private fun runningVersion() = YoutubeDL.execute(YoutubeDLRequest(emptyList<String>()).addOption("--version"), null, false, null).out.trim()

    fun checkUpdate(): EngineRelease? {
        initialize()
        val release = latestRelease()
        return lock.read {
            val installed = EngineFiles.version(executable)
            val current = installed != null && !EngineFiles.olderThan(installed, release.version) && runningVersion() == installed &&
                (installed != release.version || EngineFiles.sha256(executable).equals(release.sha256, true))
            if (current) null else release
        }
    }

    private fun latestRelease(): EngineRelease {
        val release = JSONObject(fetch("https://api.github.com/repos/$REPOSITORY/releases/latest").toString(Charsets.UTF_8))
        val version = release.getString("tag_name")
        check(version.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}(\\.\\d+)?"))) { "The update service returned an unrecognized release." }
        val assets = release.getJSONArray("assets")
        val binary = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == "yt-dlp" }
            ?: throw IOException("The official release has no Android-compatible Python engine.")
        val prefix = "https://github.com/$REPOSITORY/releases/download/$version/"
        val url = binary.getString("browser_download_url")
        check(url == prefix + "yt-dlp") { "The update service returned an unexpected engine URL." }
        val digest = binary.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: fetch(prefix + "SHA2-256SUMS").toString(Charsets.UTF_8).lineSequence()
                .map { it.trim().split(Regex("\\s+"), limit = 2) }.firstOrNull { it.size == 2 && it[1].removePrefix("*") == "yt-dlp" }?.get(0)
            ?: throw IOException("The update service did not provide an engine checksum.")
        return EngineRelease(version, url, digest)
    }

    fun update(release: EngineRelease) {
        initialize()
        // Fetch outside the write lock so ongoing downloads are not held up by the network.
        val candidate = File.createTempFile("yt-dlp-update-", ".tmp", executable.parentFile)
        try {
            candidate.writeBytes(fetch(release.url, MAX_ENGINE_BYTES))
            lock.write {
                val installed = EngineFiles.version(executable)
                // A different check may have installed a newer release while this download ran.
                if (installed != null && installed != release.version && !EngineFiles.olderThan(installed, release.version) && runningVersion() == installed) return@write
                EngineFiles.install(candidate, executable, release.version, release.sha256, ::runningVersion)
            }
        } catch (e: Exception) {
            throw IOException("Engine update failed. Installed version: ${installedVersion()}. ${e.message}", e)
        } finally { candidate.delete() }
    }

    private fun fetch(url: String, maxBytes: Int = 2 * 1024 * 1024): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "StreamPocket/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Could not reach the update service (HTTP $status). Try again later.")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw IOException("The update response was larger than expected.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    companion object {
        const val BUNDLED_VERSION = "2026.10.10.233141"
        const val BUNDLED_SHA256 = "f40b6a9e4c5786360953911cab5d2f87507626b8fb7bfee3f8f1c2e5cf341fae"
        private const val REPOSITORY = "yt-dlp/yt-dlp-nightly-builds"
        private const val MAX_ENGINE_BYTES = 30 * 1024 * 1024
        private val lock = ReentrantReadWriteLock(true)
        @Volatile private var prepared = false
    }
}
