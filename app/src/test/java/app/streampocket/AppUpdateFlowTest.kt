package app.streampocket

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateFlowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val release = AppRelease("0.6.0", "https://github.com/gatofile/StreamPocket/releases/download/v0.6.0/StreamPocket.apk", "a".repeat(64), 3)
    private fun start(source: AppUpdateSource): AppUpdateController {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val media = object : MediaSource {
            override fun inspect(url: String): Media = error("Unexpected extraction")
            override fun search(query: String, count: Int): List<Media> = emptyList()
            override fun songVersion(original: Media): Media? = null
            override fun checkEngineUpdate(): EngineRelease? = null
            override fun updateEngine(release: EngineRelease) = Unit
            override fun engineVersion() = EngineManager.BUNDLED_VERSION
        }
        val vm = AppViewModel(context, media, source)
        ui.setContent { StreamPocketTheme { StreamPocket(vm = vm) } }
        return vm.appUpdate
    }
    private open class Source : AppUpdateSource {
        override fun check(): AppRelease? = null
        override fun download(release: AppRelease, progress: (Int) -> Unit): File = error("Unexpected download")
    }
    private fun awaitState(update: AppUpdateController, state: AppUpdateState) {
        ui.waitUntil(5000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); update.state == state }
        ui.waitForIdle()
    }
    @Test fun launchChecksOnceAndAnAvailableUpdateNeedsConsent() {
        val checks = AtomicInteger()
        val downloads = AtomicInteger()
        val update = start(object : Source() {
            override fun check(): AppRelease { checks.incrementAndGet(); return release }
            override fun download(release: AppRelease, progress: (Int) -> Unit): File { downloads.incrementAndGet(); return super.download(release, progress) }
        })
        awaitState(update, AppUpdateState.AVAILABLE)
        ui.onNodeWithText("StreamPocket 0.6.0 is available. Update now?").assertIsDisplayed()
        ui.onNodeWithText("Later").performClick()
        ui.runOnIdle { update.onLaunch(); update.onLaunch(); assertFalse(update.showDialog) }
        assertEquals(1, checks.get()); assertEquals(0, downloads.get())
    }
    @Test fun currentAppIsQuietAtLaunchAndUpdatesAreFirstInSettings() {
        val update = start(Source())
        awaitState(update, AppUpdateState.CURRENT)
        ui.onNode(isDialog()).assertDoesNotExist()
        ui.onNodeWithText("Settings").performClick()
        val appRow = ui.onNodeWithText("App update").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val quality = ui.onNodeWithText("Quality").fetchSemanticsNode().boundsInRoot
        assertTrue(appRow.top < quality.top)
        ui.onNodeWithText("Check for updates", substring = false).performClick()
        awaitState(update, AppUpdateState.CURRENT)
        ui.onNodeWithText("StreamPocket is up to date.").assertIsDisplayed()
        screenshot("preview-app-update-current.png")
    }
    @Test fun failedLaunchCheckIsQuietAndManualCheckCanRetry() {
        val checks = AtomicInteger()
        val update = start(object : Source() {
            override fun check(): AppRelease? { if (checks.incrementAndGet() <= 2) throw java.io.IOException(); return null }
        })
        awaitState(update, AppUpdateState.CHECK_FAILED)
        ui.onNode(isDialog()).assertDoesNotExist()
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Check for updates", substring = false).performClick()
        awaitState(update, AppUpdateState.CHECK_FAILED)
        ui.onNodeWithText("Couldn't check for app updates. Try again.").assertIsDisplayed()
        ui.onNodeWithText("Retry").performClick()
        awaitState(update, AppUpdateState.CURRENT)
        ui.onNodeWithText("StreamPocket is up to date.").assertIsDisplayed()
    }
    @Test fun confirmedDownloadShowsProgressAndCanBeCancelled() {
        val gate = CountDownLatch(1)
        val update = start(object : Source() {
            override fun check() = release
            override fun download(release: AppRelease, progress: (Int) -> Unit): File { progress(42); gate.await(30, TimeUnit.SECONDS); error("Cancelled") }
        })
        try {
            awaitState(update, AppUpdateState.AVAILABLE)
            screenshot("preview-app-update-available.png")
            ui.onNodeWithText("Update", substring = false).performClick()
            ui.waitUntil(5000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); update.progress == 42 }
            ui.onNodeWithText("Downloading update… 42%").assertIsDisplayed()
            ui.onNodeWithText("Cancel").performClick()
            ui.runOnIdle { assertEquals(AppUpdateState.AVAILABLE, update.state); assertFalse(update.showDialog); assertNull(update.readyFile) }
        } finally { gate.countDown() }
    }
    @Test fun failedDownloadCanRetryAndHandsOffToAndroidInstallPermission() {
        val downloads = AtomicInteger()
        val file = File(context.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        val update = start(object : Source() {
            override fun check() = release
            override fun download(release: AppRelease, progress: (Int) -> Unit): File {
                if (downloads.incrementAndGet() == 1) throw java.io.IOException()
                return file
            }
        })
        awaitState(update, AppUpdateState.AVAILABLE)
        ui.onNodeWithText("Update", substring = false).performClick()
        awaitState(update, AppUpdateState.DOWNLOAD_FAILED)
        ui.onNodeWithText("Couldn't download or verify the update. Try again.").assertIsDisplayed()
        ui.onNodeWithText("Retry").performClick()
        awaitState(update, AppUpdateState.READY)
        val request = Shadows.shadowOf(ui.activity).nextStartedActivityForResult
        assertNotNull(request)
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.intent.action)
        assertEquals("package:${context.packageName}", request.intent.data.toString())
        Shadows.shadowOf(ui.activity).receiveResult(request.intent, android.app.Activity.RESULT_CANCELED, null)
        ui.onNodeWithText("Allow StreamPocket to install updates, then tap Install.").assertIsDisplayed()
        assertEquals(2, downloads.get())
    }
    private fun screenshot(name: String) {
        ui.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(System.getProperty("preview.dir"), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
