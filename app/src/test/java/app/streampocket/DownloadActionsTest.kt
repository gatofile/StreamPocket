package app.streampocket

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadActionsTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun swipeRevealsTrashAndFileRemovalRequiresAnExplicitChoice() {
        var decision: Boolean? = null
        ui.setContent {
            StreamPocketTheme {
                var asking by remember { mutableStateOf(false) }
                Column(Modifier.padding(24.dp)) {
                    SwipeToRevealDelete("example", onDelete = { asking = true }) {
                        Surface(Modifier.fillMaxWidth().height(140.dp)) { Text("A saved song", Modifier.fillMaxSize().padding(20.dp)) }
                    }
                }
                if (asking) DeleteDownloadDialog("A saved song", "MP3", true, false,
                    onDismiss = { asking = false }, onDelete = { decision = it; asking = false })
            }
        }
        ui.onNodeWithContentDescription("Delete download").assertDoesNotExist()
        ui.onNodeWithText("A saved song").performTouchInput { swipeLeft() }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("Delete download").assertIsDisplayed()
        assertNull(decision)
        screenshot("preview-swipe-delete.png")
        ui.onNodeWithContentDescription("Delete download").performClick()
        ui.onNodeWithText("Delete download?").assertIsDisplayed()
        ui.onNodeWithText("Also delete the MP3 file from this phone").assertIsDisplayed()
        ui.onNode(isToggleable()).assertIsOff()
        screenshot("preview-delete.png")
        ui.onNode(isToggleable()).performClick()
        ui.onNodeWithText("Delete", substring = false).performClick()
        assertEquals(true, decision)
    }
    @Test fun deletingAnEntryKeepsTheFileUnlessTheCheckboxIsSelected() {
        var decision: Boolean? = null
        ui.setContent { StreamPocketTheme { DeleteDownloadDialog("Saved video", "MP4", true, false, {}, { decision = it }) } }
        ui.onNode(isToggleable()).assertIsOff()
        ui.onNodeWithText("Delete", substring = false).performClick()
        assertEquals(false, decision)
    }
    @Test fun cancellingThePromptDoesNotDeleteAnything() {
        var deleted = false
        var dismissed = false
        ui.setContent { StreamPocketTheme { DeleteDownloadDialog("Saved video", "MP4", true, false, { dismissed = true }, { deleted = true }) } }
        ui.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertFalse(deleted)
    }
    @Test fun savedDownloadsHaveSeparateOpenAndShowInFolderActions() {
        var opened = false
        var shown = false
        ui.setContent { StreamPocketTheme { SavedDownloadActions({ opened = true }, { shown = true }) } }
        ui.onNodeWithText("Show in folder").performClick()
        assertTrue(shown)
        assertFalse(opened)
        ui.onNodeWithText("Open", substring = false).performClick()
        assertTrue(opened)
    }
    @Test fun aSavedDownloadOpensItsActualFolder() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val vm = AppViewModel(context)
        val job = androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.SUCCEEDED,
            setOf("download", "name:Example song", "format:MP3"), outputData = androidx.work.workDataOf(
                "uri" to "content://media/external/audio/media/42", "mime" to "audio/mpeg", "folder" to ""))
        ui.setContent { StreamPocketTheme { Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { Downloads(vm, listOf(job)) } } }
        val shadow = org.robolectric.Shadows.shadowOf(context)
        while (shadow.nextStartedActivity != null) { }
        screenshot("preview-downloads.png")
        ui.onNodeWithText("Show in folder").performClick()
        var intent: android.content.Intent? = null
        ui.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (intent == null) intent = shadow.nextStartedActivity
            intent != null
        }
        assertEquals(android.content.Intent.ACTION_VIEW, intent!!.action)
        assertEquals(DownloadFolders.defaultFolder(Format.MP3), intent!!.data)
    }
    @Test fun formatTabsSeparateSavedQueuedAndFailedDownloads() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val vm = AppViewModel(context)
        fun job(name: String, format: Format, state: androidx.work.WorkInfo.State) = androidx.work.WorkInfo(
            java.util.UUID.randomUUID(), state, setOf("download", "name:$name", "format:${format.name}"),
            outputData = androidx.work.workDataOf("uri" to "content://media/external/${if (format == Format.MP3) "audio" else "video"}/media/42", "mime" to format.mime))
        val jobs = listOf(job("Saved song", Format.MP3, androidx.work.WorkInfo.State.SUCCEEDED),
            job("Queued song", Format.MP3, androidx.work.WorkInfo.State.ENQUEUED),
            job("Saved video", Format.MP4, androidx.work.WorkInfo.State.SUCCEEDED),
            job("Failed video", Format.MP4, androidx.work.WorkInfo.State.FAILED))
        ui.setContent { StreamPocketTheme { Surface(Modifier.fillMaxSize()) { Downloads(vm, jobs) } } }
        ui.onNodeWithText("MP3").assertIsSelected()
        ui.onNodeWithText("Saved song").assertIsDisplayed()
        ui.onNodeWithText("Queued song").assertIsDisplayed()
        ui.onNodeWithText("Saved video").assertDoesNotExist()
        ui.onNodeWithText("Failed video").assertDoesNotExist()
        screenshot("preview-downloads-mp3.png")
        ui.onNodeWithText("MP4").performClick().assertIsSelected()
        ui.onNodeWithText("Saved song").assertDoesNotExist()
        ui.onNodeWithText("Queued song").assertDoesNotExist()
        ui.onNodeWithText("Saved video").assertIsDisplayed()
        ui.onNodeWithText("Failed video").assertIsDisplayed()
        ui.onNodeWithText("Retry").assertIsDisplayed()
        ui.onNodeWithText("Show in folder").assertIsDisplayed()
        screenshot("preview-downloads-mp4.png")
        ui.onNodeWithText("Saved video").performTouchInput { swipeLeft() }
        ui.onNodeWithContentDescription("Delete download").performClick()
        ui.onNodeWithText("Also delete the MP4 file from this phone").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("MP3").performClick()
        ui.onNodeWithText("Saved song").assertIsDisplayed()
        ui.runOnIdle { assertEquals(Format.MP3, vm.downloadFormat) }
    }
    @Test fun emptyStateBelongsToTheSelectedFormat() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val vm = AppViewModel(context)
        val job = androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.ENQUEUED,
            setOf("download", "name:Audio only", "format:MP3"))
        ui.setContent { StreamPocketTheme { Downloads(vm, listOf(job)) } }
        ui.onNodeWithText("MP4").performClick()
        ui.onNodeWithText("No MP4 downloads").assertIsDisplayed()
        ui.onNodeWithText("Audio only").assertDoesNotExist()
        ui.onNodeWithText("MP3").performClick()
        ui.onNodeWithText("Audio only").assertIsDisplayed()
        ui.onNodeWithText("No MP4 downloads").assertDoesNotExist()
    }
    @Test fun deleteAllRequiresConfirmationAndIncludesBothFormatTabs() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val vm = AppViewModel(context).apply { downloadFormat = Format.MP4 }
        val jobs = Format.entries.map { format -> androidx.work.WorkInfo(java.util.UUID.randomUUID(), androidx.work.WorkInfo.State.SUCCEEDED,
            setOf("download", "name:Saved ${format.name}", "format:${format.name}"),
            outputData = androidx.work.workDataOf("uri" to "content://media/external/${format.name}/42")) }
        ui.setContent { StreamPocketTheme { Downloads(vm, jobs) } }
        ui.onNodeWithContentDescription("Delete all downloads").performClick()
        ui.onNodeWithText("Delete all downloads?").assertIsDisplayed()
        ui.onNodeWithText("Remove all 2 downloads from both the MP3 and MP4 tabs?").assertIsDisplayed()
        ui.onNode(isToggleable()).assertIsOff()
        screenshot("preview-delete-all.png")
        ui.onNodeWithText("Cancel").performClick()
        assertTrue(DownloadHistory(context).removed().isEmpty())
        ui.onNodeWithContentDescription("Delete all downloads").performClick()
        ui.onNodeWithText("Delete all", substring = false).performClick()
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.removingDownloads.isEmpty() }
        assertEquals(jobs.map { it.id.toString() }.toSet(), DownloadHistory(context).removed())
        ui.runOnIdle { assertNull(vm.error) }
    }
    @Test fun deletingAllPhoneFilesRequiresAnExplicitCheckboxChoice() {
        var decision: Boolean? = null
        ui.setContent { StreamPocketTheme { DeleteAllDownloadsDialog(3, true, {}, { decision = it }) } }
        ui.onNodeWithText("Active downloads will also be cancelled.").assertIsDisplayed()
        ui.onNode(isToggleable()).assertIsOff().performClick()
        ui.onNodeWithText("Delete all", substring = false).performClick()
        assertEquals(true, decision)
    }
    @Test fun deleteAllIsDisabledForAnEmptyDownloadList() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val vm = AppViewModel(context)
        ui.setContent { StreamPocketTheme { Downloads(vm, emptyList()) } }
        ui.onNodeWithContentDescription("Delete all downloads").assertIsNotEnabled()
        ui.onNodeWithText("Delete all downloads?").assertDoesNotExist()
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
