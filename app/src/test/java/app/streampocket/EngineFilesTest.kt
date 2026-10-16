package app.streampocket

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EngineFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun engine(name: String, version: String): File = File(temp.root, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("yt_dlp/version.py"))
            zip.write("__version__ = '$version'\n".toByteArray())
            zip.closeEntry()
        }
    }
    @Test fun detectsVersionInExecutableRatherThanAnExternalLabel() {
        assertEquals("2025.11.12", EngineFiles.version(engine("yt-dlp", "2025.11.12")))
        assertNull(EngineFiles.version(File(temp.root, "missing")))
    }
    @Test fun readsVersionAfterTheOfficialExecutableShebang() {
        val archive = engine("yt-dlp", "2026.10.10.233141")
        archive.writeBytes("#!/usr/bin/env python3\n".toByteArray() + archive.readBytes())
        assertEquals("2026.10.10.233141", EngineFiles.version(archive))
    }
    @Test fun readsVersionWithWindowsLineEndingsInTheExecutableHeader() {
        val archive = engine("yt-dlp", "2026.10.10.233141")
        archive.writeBytes("#!/usr/bin/env python3\r\n".toByteArray() + archive.readBytes())
        assertEquals("2026.10.10.233141", EngineFiles.version(archive))
    }
    @Test fun validatesAndActivatesTheActualBundledExecutable() {
        val asset = File(requireNotNull(System.getProperty("engine.asset")))
        val candidate = File(temp.root, "candidate").also { asset.copyTo(it) }
        val installed = engine("yt-dlp", "2025.11.12")
        EngineFiles.install(candidate, installed, EngineManager.BUNDLED_VERSION, EngineManager.BUNDLED_SHA256) {
            EngineFiles.version(installed)!!
        }
        assertEquals(EngineManager.BUNDLED_VERSION, EngineFiles.version(installed))
        assertEquals(EngineManager.BUNDLED_SHA256, EngineFiles.sha256(installed))
    }
    @Test fun comparesReleaseNumbersNumerically() {
        assertTrue(EngineFiles.olderThan("2025.11.12", "2026.08.19"))
        assertFalse(EngineFiles.olderThan("2026.08.19", "2026.08.19"))
        assertFalse(EngineFiles.olderThan("2026.10.01", "2026.08.19"))
        assertTrue(EngineFiles.olderThan(null, "2026.08.19"))
    }
    @Test fun invalidChecksumLeavesInstalledEngineUntouched() {
        val installed = engine("yt-dlp", "2025.11.12")
        val candidate = engine("new", "2026.08.19")
        assertThrows(IllegalStateException::class.java) {
            EngineFiles.install(candidate, installed, "2026.08.19", "0".repeat(64)) { fail("Must not execute unverified code"); "" }
        }
        assertEquals("2025.11.12", EngineFiles.version(installed))
    }
    @Test fun correctEngineMustRunBeforeInstallationIsAccepted() {
        val installed = engine("yt-dlp", "2025.11.12")
        val candidate = engine("new", "2026.08.19")
        var verified = false
        EngineFiles.install(candidate, installed, "2026.08.19", EngineFiles.sha256(candidate)) {
            verified = true
            EngineFiles.version(installed)!!
        }
        assertTrue(verified)
        assertEquals("2026.08.19", EngineFiles.version(installed))
        assertFalse(File(temp.root, "yt-dlp.previous").exists())
    }
    @Test fun runtimeVersionMismatchRollsBackThePreviousEngine() {
        val installed = engine("yt-dlp", "2025.11.12")
        val candidate = engine("new", "2026.08.19")
        assertThrows(java.io.IOException::class.java) {
            EngineFiles.install(candidate, installed, "2026.08.19", EngineFiles.sha256(candidate)) { "2025.11.12" }
        }
        assertEquals("2025.11.12", EngineFiles.version(installed))
    }
    @Test fun bundledEngineMatchesThePublishedReleaseChecksum() {
        val asset = File(requireNotNull(System.getProperty("engine.asset")))
        assertEquals(EngineManager.BUNDLED_VERSION, EngineFiles.version(asset))
        assertEquals(EngineManager.BUNDLED_SHA256, EngineFiles.sha256(asset))
    }
}
