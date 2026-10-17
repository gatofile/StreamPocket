package app.streampocket

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock

data class AppRelease(val version: String, val url: String, val sha256: String, val size: Long) {
    init {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(url == "https://github.com/gatofile/StreamPocket/releases/download/v$version/StreamPocket.apk")
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(size in 1..MAX_APK_BYTES)
    }
    companion object { const val MAX_APK_BYTES = 512L * 1024 * 1024 }
}

interface AppUpdateSource {
    fun check(): AppRelease?
    fun download(release: AppRelease, progress: (Int) -> Unit): File
}

object AppReleaseRules {
    fun newer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<Int>? = value.removePrefix("v").takeIf {
            it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))
        }?.split('.')?.map { it.toIntOrNull() ?: return null }
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
    fun parse(json: JSONObject, installed: String): AppRelease? {
        check(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "No public app release available." }
        val tag = json.getString("tag_name")
        check(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Unrecognized app version." }
        if (!newer(tag, installed)) return null
        val assets = json.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .singleOrNull { it.optString("name") == "StreamPocket.apk" && it.optString("state") == "uploaded" }
            ?: throw IOException("This release has no universal APK.")
        return AppRelease(tag.removePrefix("v"), asset.getString("browser_download_url"),
            asset.getString("digest").removePrefix("sha256:"), asset.getLong("size"))
    }
}

class AppUpdateManager(private val context: Context) : AppUpdateSource {
    private val downloadLock = ReentrantLock()
    override fun check(): AppRelease? {
        val connection = connection("https://api.github.com/repos/gatofile/StreamPocket/releases/latest")
        try {
            if (connection.responseCode != 200) throw IOException("App update check unavailable.")
            val bytes = connection.inputStream.use { it.readBytesLimited(2 * 1024 * 1024) }
            return AppReleaseRules.parse(JSONObject(bytes.toString(Charsets.UTF_8)), BuildConfig.VERSION_NAME)
        } finally { connection.disconnect() }
    }

    override fun download(release: AppRelease, progress: (Int) -> Unit): File {
        downloadLock.lockInterruptibly()
        try { return downloadLocked(release, progress) } finally { downloadLock.unlock() }
    }

    private fun downloadLocked(release: AppRelease, progress: (Int) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(directory, "StreamPocket-${release.version}.apk")
        if (destination.exists()) {
            if (runCatching { verify(destination, release) }.isSuccess) return destination
            destination.delete()
        }
        directory.listFiles()?.forEach { it.delete() }
        val partial = File(directory, "update.part")
        val connection = connection(release.url)
        try {
            if (connection.responseCode != 200) throw IOException("App download unavailable.")
            check(connection.contentLengthLong <= AppRelease.MAX_APK_BYTES) { "Update is too large." }
            var total = 0L
            var lastPercent = -1
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= release.size) { "Unexpected update size." }
                    output.write(buffer, 0, count)
                    val percent = (total * 100 / release.size).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } }
            verify(partial, release)
            check(partial.renameTo(destination)) { "Couldn't save the update." }
            return destination
        } finally { connection.disconnect(); partial.delete() }
    }

    @Suppress("DEPRECATION")
    internal fun verify(file: File, release: AppRelease) {
        check(file.length() == release.size && EngineFiles.sha256(file).equals(release.sha256, true)) { "App update checksum failed." }
        val manager = context.packageManager
        val apk = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("Invalid app update.")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(apk.packageName == context.packageName && apk.versionName == release.version && apk.longVersionCode > installed.longVersionCode) { "Incorrect app update." }
        val currentSigners = installed.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val updateSigners = apk.signingInfo?.apkContentsSigners.orEmpty().toSet()
        check(currentSigners.isNotEmpty() && currentSigners == updateSigners) { "App update signature does not match." }
    }

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; useCaches = false
        setRequestProperty("User-Agent", "StreamPocket/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            check(output.size() + count <= limit) { "Update response too large." }
            output.write(buffer, 0, count)
        }
    }
}

object AppUpdateInstaller {
    fun intent(context: Context, file: File): Intent {
        require(file.parentFile?.canonicalFile == File(context.cacheDir, "updates").canonicalFile && file.isFile)
        return intent(FileProvider.getUriForFile(context, "${context.packageName}.updates", file))
    }
    internal fun intent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        require(uri.scheme == "content")
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
