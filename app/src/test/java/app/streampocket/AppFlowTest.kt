package app.streampocket

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.Configuration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun start(source: MediaSource? = null): AppViewModel {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val vm = AppViewModel(context, source ?: FakeSource(Media("test", "", "Test")), object : AppUpdateSource {
            override fun check(): AppRelease? = null
            override fun download(release: AppRelease, progress: (Int) -> Unit): File = error("No app update expected")
        })
        ui.setContent { StreamPocketTheme { StreamPocket(vm = vm) } }
        ui.waitForIdle()
        return vm
    }
    @Test fun defaultsAndNavigationAreVisible() {
        val vm = start()
        ui.onNodeWithText("Search videos").assertIsDisplayed()
        screenshot("preview-discover.png")
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("192 kbps").assertIsDisplayed()
        screenshot("preview-settings.png")
        ui.onNodeWithText("192 kbps").performClick()
        ui.onNodeWithText("256 kbps").performClick()
        ui.runOnIdle { assertEquals(256, vm.settings.audioQuality) }
        ui.onNodeWithText("Downloads").performClick()
        ui.onNodeWithText("No MP3 downloads").assertIsDisplayed()
    }
    @Test fun invalidUrlShowsActionableErrorWithoutNetwork() {
        start()
        ui.onNode(hasSetTextAction()).performTextInput("https://example.com/video")
        ui.onNodeWithText("Search videos").performClick()
        ui.onNodeWithText("Paste a valid YouTube video link, or search by name.").assertIsDisplayed()
    }
    @Test fun donationButtonOpensTheRequestedKofiPage() {
        start()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadow = org.robolectric.Shadows.shadowOf(context)
        while (shadow.nextStartedActivity != null) { /* Discard the test activity's launch intent. */ }
        ui.onNodeWithText("Settings").performClick()
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Donate on Ko-fi"))
        screenshot("preview-donate.png")
        ui.onNodeWithText("Donate on Ko-fi").performClick()
        val intent = shadow.nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(DONATION_URL, intent.data.toString())
    }
    @Test fun currentEngineShowsOnlyASimpleStatusWithoutInstalling() {
        val checks = AtomicInteger()
        val installs = AtomicInteger()
        val vm = start(object : FakeSource(Media("test", "", "Test")) {
            override fun checkEngineUpdate(): EngineRelease? { checks.incrementAndGet(); return null }
            override fun updateEngine(release: EngineRelease) { installs.incrementAndGet() }
        })
        checkEngineFromSettings(vm, EngineUpdateState.CURRENT)
        ui.onNode(hasText("Up to date") and hasAnyAncestor(hasTestTag("engine-update-setting"))).assertIsDisplayed()
        ui.onNodeWithText("Update", substring = false).assertDoesNotExist()
        ui.onNode(isDialog()).assertDoesNotExist()
        ui.onNodeWithContentDescription("Check for updates").performClick()
        awaitEngineState(vm, EngineUpdateState.CURRENT)
        ui.onNodeWithText("The download engine is up to date.").assertIsDisplayed()
        ui.onNode(hasText("Update") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        screenshot("preview-engine-current.png")
        ui.onNodeWithText("OK").performClick()
        ui.runOnIdle { assertFalse(vm.showEngineUpdateDialog); assertNull(vm.error) }
        ui.onNodeWithText("Update", substring = false).assertDoesNotExist()
        assertEquals(2, checks.get())
        assertEquals(0, installs.get())
    }
    @Test fun availableEngineInstallsOnlyAfterConfirmationAndCanBeCancelled() {
        val installs = AtomicInteger()
        val installed = AtomicBoolean(false)
        val gate = CountDownLatch(1)
        val vm = start(object : FakeSource(Media("test", "", "Test")) {
            override fun checkEngineUpdate() = nextRelease
            override fun updateEngine(release: EngineRelease) {
                assertEquals(nextRelease, release)
                installs.incrementAndGet()
                gate.await(30, TimeUnit.SECONDS)
                installed.set(true)
            }
            override fun engineVersion() = if (installed.get()) nextRelease.version else EngineManager.BUNDLED_VERSION
        })
        try {
            checkEngineFromSettings(vm, EngineUpdateState.AVAILABLE)
            ui.onNodeWithText("Update available").assertIsDisplayed()
            ui.onNode(isDialog()).assertDoesNotExist()
            ui.onNodeWithText("Update").performClick()
            ui.onNodeWithText("An update is available.").assertIsDisplayed()
            screenshot("preview-engine-update.png")
            assertEquals(0, installs.get())
            ui.onNodeWithText("Cancel").performClick()
            ui.runOnIdle { assertFalse(vm.showEngineUpdateDialog); assertTrue(vm.engineUpdateAvailable) }
            assertEquals(0, installs.get())
            ui.onNodeWithText("Update").performClick()
            ui.onNode(hasText("Update") and hasAnyAncestor(isDialog())).performClick()
            ui.onNode(hasText("Updating…") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            gate.countDown()
            awaitEngineState(vm, EngineUpdateState.CURRENT)
            ui.onNodeWithText("The download engine is up to date.").assertIsDisplayed()
            ui.runOnIdle { assertEquals(nextRelease.version, vm.engineVersion); assertNull(vm.error) }
            ui.onNodeWithText("OK").performClick()
            ui.onNodeWithText("Update", substring = false).assertDoesNotExist()
            assertEquals(1, installs.get())
        } finally { gate.countDown() }
    }
    @Test fun failedCheckDoesNotClaimCurrentAndCanBeRetried() {
        val checks = AtomicInteger()
        val vm = start(object : FakeSource(Media("test", "", "Test")) {
            override fun checkEngineUpdate(): EngineRelease? {
                if (checks.incrementAndGet() == 1) throw java.io.IOException("HTTP 503: internal service detail")
                return null
            }
        })
        checkEngineFromSettings(vm, EngineUpdateState.CHECK_FAILED)
        ui.onNodeWithText("Couldn't check for updates").assertIsDisplayed()
        ui.onNodeWithText("Update", substring = false).assertDoesNotExist()
        ui.onNode(hasText("Up to date") and hasAnyAncestor(hasTestTag("engine-update-setting"))).assertDoesNotExist()
        ui.onNodeWithText("The download engine is up to date.").assertDoesNotExist()
        ui.onNodeWithText("HTTP 503", substring = true).assertDoesNotExist()
        ui.onNodeWithContentDescription("Check for updates").performClick()
        awaitEngineState(vm, EngineUpdateState.CURRENT)
        assertEquals(2, checks.get())
    }
    @Test fun failedInstallKeepsTheUpdateAvailableForRetry() {
        val installs = AtomicInteger()
        val vm = start(object : FakeSource(Media("test", "", "Test")) {
            override fun checkEngineUpdate() = nextRelease
            override fun updateEngine(release: EngineRelease) {
                if (installs.incrementAndGet() == 1) throw java.io.IOException("Checksum verification failed")
            }
        })
        checkEngineFromSettings(vm, EngineUpdateState.AVAILABLE)
        ui.onNodeWithText("Update").performClick()
        ui.onNode(hasText("Update") and hasAnyAncestor(isDialog())).performClick()
        awaitEngineState(vm, EngineUpdateState.UPDATE_FAILED)
        ui.onNodeWithText("Couldn't update the download engine. Try again.").assertIsDisplayed()
        ui.onNodeWithText("The download engine is up to date.").assertDoesNotExist()
        ui.onNodeWithText("Retry").performClick()
        awaitEngineState(vm, EngineUpdateState.CURRENT)
        assertEquals(2, installs.get())
    }
    private val nextRelease = EngineRelease("2026.10.16.123456",
        "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/2026.10.16.123456/yt-dlp", "a".repeat(64))
    @Test fun updateActionStaysHiddenWhileAnAutomaticCheckIsPending() {
        val gate = CountDownLatch(1)
        val vm = start(object : FakeSource(Media("test", "", "Test")) {
            override fun checkEngineUpdate(): EngineRelease? { gate.await(30, TimeUnit.SECONDS); return null }
        })
        try {
            ui.onNodeWithText("Settings").performClick()
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Download engine"))
            ui.onNodeWithText("Checking for updates…").assertIsDisplayed()
            ui.onNodeWithText("Update", substring = false).assertDoesNotExist()
            ui.onNode(isDialog()).assertDoesNotExist()
            gate.countDown()
            awaitEngineState(vm, EngineUpdateState.CURRENT)
            ui.onNode(hasText("Up to date") and hasAnyAncestor(hasTestTag("engine-update-setting"))).assertIsDisplayed()
            ui.onNodeWithText("Update", substring = false).assertDoesNotExist()
        } finally { gate.countDown() }
    }
    private fun checkEngineFromSettings(vm: AppViewModel, expected: EngineUpdateState) {
        ui.onNodeWithText("Settings").performClick()
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Download engine"))
        awaitEngineState(vm, expected)
    }
    private fun awaitEngineState(vm: AppViewModel, expected: EngineUpdateState) {
        ui.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.engineUpdateState == expected && (expected == EngineUpdateState.INSTALLING || !vm.updatingEngine)
        }
        ui.waitForIdle()
    }
    @Test fun reviewAllowsFormatChangeAndMetadataEditing() {
        val vm = start()
        val media = Media("abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk", "An example song", "Example Artist - Topic", 210, track = "An example song", artist = "Example Artist", album = "Example Album")
        ui.runOnIdle { vm.review = Review(media, media, Tags.from(media), Format.MP3, 192, "", message = "Review the details before downloading.") }
        ui.onNodeWithText("Download", substring = false).assertIsDisplayed()
        screenshot("preview-review.png")
        ui.onNodeWithText("MP4", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Download MP4 · 1080p").assertIsDisplayed()
        ui.onNodeWithText("Edit metadata").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("Example Artist", substring = false)).assertExists()
        ui.runOnIdle { assert(vm.review!!.format == Format.MP4) }
        ui.onNodeWithText("Download MP4 · 1080p").performClick()
        ui.onNodeWithText("Save this MP4 here?").assertDoesNotExist()
        ui.onNodeWithText("Download", substring = false).assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, vm.tab); assertEquals(Format.MP4, vm.downloadFormat) }
        ui.onNode(hasText("MP4") and isSelectable()).assertIsSelected()
    }
    @Test fun reviewOpensBeforeMetadataAndPreservesEditsAsItLoads() {
        val release = CountDownLatch(1)
        val item = Media("abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk", "Search title")
        val detailed = item.copy(title = "Official title", album = "Album from source")
        val source = FakeSource(detailed, release)
        val vm = start(source)
        try {
            ui.runOnIdle { vm.select(item); assertNotNull(vm.review); assertTrue(vm.review!!.metadataLoading) }
            ui.onNodeWithText("Download", substring = false).assertIsDisplayed()
            ui.onNodeWithText("Download MP3 · 192 kbps").assertIsNotEnabled()
            ui.runOnIdle { vm.review = vm.review!!.copy(tags = vm.review!!.tags.copy(title = "My edited title"), format = Format.MP4, quality = 720, folder = "content://chosen-folder") }
            release.countDown()
            ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.review?.metadataLoading == false }
            ui.runOnIdle {
                assertEquals("My edited title", vm.review!!.tags.title)
                assertEquals("Album from source", vm.review!!.tags.album)
                assertEquals(720, vm.review!!.quality)
                assertEquals("content://chosen-folder", vm.review!!.folder)
            }
            ui.onNodeWithText("Download MP4 · 720p").assertIsEnabled()
        } finally { release.countDown(); ui.runOnIdle { vm.dismissReview() } }
    }
    @Test fun songMatchingDoesNotBlockDownloadingAndCannotResurrectDismissedReview() {
        val releaseMatch = CountDownLatch(1)
        val matchingStarted = AtomicBoolean(false)
        val original = Media("abcdefghijk", "https://www.youtube.com/watch?v=abcdefghijk", "Artist - Song", "Artist", 260, track = "Song", artist = "Artist")
        val source = object : FakeSource(original) {
            override fun songVersion(original: Media): Media? {
                matchingStarted.set(true)
                releaseMatch.await(30, TimeUnit.SECONDS)
                return original.copy(id = "song-release", channel = "Artist - Topic", album = "Album")
            }
        }
        val vm = start(source)
        try {
            ui.runOnIdle { vm.select(original) }
            ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); matchingStarted.get() && vm.review?.matching == true }
            ui.onNodeWithText("Download MP3 · 192 kbps").assertIsEnabled()
            ui.runOnIdle { vm.dismissReview() }
            releaseMatch.countDown()
            ui.waitForIdle()
            ui.runOnIdle { assertNull(vm.review) }
        } finally { releaseMatch.countDown(); ui.runOnIdle { vm.dismissReview() } }
    }
    private open class FakeSource(private val item: Media, private val gate: CountDownLatch? = null) : MediaSource {
        override fun inspect(url: String): Media { gate?.await(30, TimeUnit.SECONDS); return item }
        override fun search(query: String, count: Int) = listOf(item)
        override fun songVersion(original: Media): Media? = null
        override fun checkEngineUpdate(): EngineRelease? = null
        override fun updateEngine(release: EngineRelease) = Unit
        override fun engineVersion() = EngineManager.BUNDLED_VERSION
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        ui.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(System.getProperty("preview.dir"), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
