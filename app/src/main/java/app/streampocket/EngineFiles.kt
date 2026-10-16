package app.streampocket

import java.io.File
import java.io.IOException
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Versions are read from the executable ZIP, never from an updater preference. */
object EngineFiles {
    fun version(file: File): String? = runCatching { readVersion(file) }.getOrNull()

    private fun readVersion(file: File): String {
        // The official executable is a ZIP with a Python shebang prepended. Android's
        // ZipFile can reject this even though desktop Java accepts the same bytes.
        // Start a streaming ZIP reader at the actual local header instead.
        PushbackInputStream(file.inputStream().buffered(), 2).use { input ->
            val first = input.read()
            val second = input.read()
            if (first == '#'.code && second == '!'.code) {
                var length = 2
                while (true) {
                    val next = input.read()
                    if (next < 0 || ++length > 4096) throw IOException("Invalid engine executable header.")
                    if (next == '\n'.code) break
                }
            } else {
                if (first < 0 || second < 0) throw IOException("The engine archive is empty.")
                input.unread(byteArrayOf(first.toByte(), second.toByte()))
            }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "yt_dlp/version.py") {
                        val text = zip.bufferedReader(Charsets.UTF_8).readText()
                        return Regex("(?m)^__version__\\s*=\\s*['\"]([^'\"]+)['\"]").find(text)?.groupValues?.get(1)
                            ?: throw IOException("The engine version declaration is missing.")
                    }
                    zip.closeEntry()
                }
            }
        }
        throw IOException("The engine archive has no yt_dlp/version.py entry.")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun olderThan(installed: String?, available: String): Boolean {
        val a = installed?.split('.')?.map { it.toIntOrNull() ?: return true } ?: return true
        val b = available.split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (comparison != 0) return comparison < 0
        }
        return false
    }

    fun install(candidate: File, destination: File, expectedVersion: String, expectedHash: String, runningVersion: () -> String) {
        check(expectedHash.matches(Regex("[0-9a-fA-F]{64}")) && sha256(candidate).equals(expectedHash, true)) { "Engine checksum verification failed. The installed engine was kept." }
        val actualVersion = readVersion(candidate)
        check(actualVersion == expectedVersion) { "Engine version $actualVersion does not match release $expectedVersion." }
        val backup = File(destination.parentFile, "yt-dlp.previous")
        destination.parentFile?.mkdirs()
        val hadPrevious = destination.exists()
        if (hadPrevious) Files.copy(destination.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(candidate.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            check(runningVersion().trim() == expectedVersion) { "The installed engine did not run as version $expectedVersion." }
            backup.delete()
        } catch (e: Exception) {
            if (hadPrevious && backup.exists()) Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else if (!hadPrevious) destination.delete()
            throw IOException("Could not activate engine $expectedVersion. Previous engine restored. ${e.message}", e)
        } finally { candidate.delete() }
    }
}
