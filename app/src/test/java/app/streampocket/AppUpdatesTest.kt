package app.streampocket

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdatesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun releaseJson(version: String = "0.6.0") = JSONObject().put("tag_name", "v$version")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray().put(JSONObject()
            .put("name", "StreamPocket.apk").put("state", "uploaded").put("size", 3)
            .put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/gatofile/StreamPocket/releases/download/v$version/StreamPocket.apk")))
    @Test fun versionsCompareNumericallyAndRejectPrereleaseOrMalformedTags() {
        assertTrue(AppReleaseRules.newer("v0.10.0", "0.9.9"))
        assertTrue(AppReleaseRules.newer("1.0.0", "0.99.99"))
        assertFalse(AppReleaseRules.newer("0.5.0", "0.5.0"))
        assertFalse(AppReleaseRules.newer("0.4.2", "0.5.0"))
        assertFalse(AppReleaseRules.newer("0.6.0-beta", "0.5.0"))
        assertFalse(AppReleaseRules.newer("999999999999.0.0", "0.5.0"))
    }
    @Test fun onlyNewerPublicUniversalReleasesAreOffered() {
        assertEquals("0.6.0", AppReleaseRules.parse(releaseJson(), "0.5.0")!!.version)
        assertNull(AppReleaseRules.parse(releaseJson("0.5.0"), "0.5.0"))
        assertNull(AppReleaseRules.parse(releaseJson("0.4.2"), "0.5.0"))
        assertThrows(IllegalStateException::class.java) { AppReleaseRules.parse(releaseJson().put("prerelease", true), "0.5.0") }
        assertThrows(IllegalStateException::class.java) { AppReleaseRules.parse(releaseJson().put("draft", true), "0.5.0") }
    }
    @Test fun untrustedUrlsMissingDigestsAndWrongAssetsAreRejected() {
        val wrongUrl = releaseJson().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/app.apk") }
        assertThrows(IllegalArgumentException::class.java) { AppReleaseRules.parse(wrongUrl, "0.5.0") }
        val missingHash = releaseJson().apply { getJSONArray("assets").getJSONObject(0).remove("digest") }
        assertThrows(Exception::class.java) { AppReleaseRules.parse(missingHash, "0.5.0") }
        val wrongAsset = releaseJson().apply { getJSONArray("assets").getJSONObject(0).put("name", "source.zip") }
        assertThrows(java.io.IOException::class.java) { AppReleaseRules.parse(wrongAsset, "0.5.0") }
        val tooLarge = releaseJson().apply { getJSONArray("assets").getJSONObject(0).put("size", AppRelease.MAX_APK_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { AppReleaseRules.parse(tooLarge, "0.5.0") }
    }
    private fun packageInfo(name: String, version: String, code: Long, signer: String) = PackageInfo().apply {
        packageName = name; versionName = version; setLongVersionCode(code)
        signingInfo = SigningInfo().also { Shadows.shadowOf(it).setSignatures(arrayOf(Signature(signer))) }
    }
    @Test fun downloadedApkMustMatchSizeDigestPackageVersionAndSigningKey() {
        val file = File(context.cacheDir, "candidate.apk").apply { writeText("apk") }
        val release = AppRelease("0.6.0", "https://github.com/gatofile/StreamPocket/releases/download/v0.6.0/StreamPocket.apk", EngineFiles.sha256(file), file.length())
        val manager = Shadows.shadowOf(context.packageManager)
        manager.installPackage(packageInfo(context.packageName, "0.5.0", 8, "1234"))
        fun candidate(name: String = context.packageName, version: String = "0.6.0", code: Long = 9, signer: String = "1234") {
            manager.setPackageArchiveInfo(file.absolutePath, packageInfo(name, version, code, signer))
        }
        val updates = AppUpdateManager(context)
        candidate(); updates.verify(file, release)
        candidate(signer = "5678"); assertThrows(IllegalStateException::class.java) { updates.verify(file, release) }
        candidate(name = "unrelated.app"); assertThrows(IllegalStateException::class.java) { updates.verify(file, release) }
        candidate(version = "0.5.1"); assertThrows(IllegalStateException::class.java) { updates.verify(file, release) }
        candidate(code = 8); assertThrows(IllegalStateException::class.java) { updates.verify(file, release) }
        candidate(); file.writeText("bad"); assertThrows(IllegalStateException::class.java) { updates.verify(file, release) }
    }
    @Test fun installerOnlySharesThePrivateUpdateDirectoryWithReadAccess() {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "update.apk").apply { writeText("apk") }
        // AndroidX FileProvider assumes '/' paths; the host here uses Windows paths.
        // Test the installer handoff separately from Android's path resolver.
        val intent = AppUpdateInstaller.intent(android.net.Uri.parse("content://${context.packageName}.updates/app_updates/${file.name}"))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertThrows(IllegalArgumentException::class.java) { AppUpdateInstaller.intent(context, File(context.filesDir, "private-data")) }
        val paths = context.resources.getXml(R.xml.update_paths)
        val roots = mutableListOf<Pair<String, String?>>()
        while (paths.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (paths.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && paths.name != "paths") roots += paths.name to paths.getAttributeValue(null, "path")
        }
        assertEquals(listOf("cache-path" to "updates/"), roots)
    }
}
