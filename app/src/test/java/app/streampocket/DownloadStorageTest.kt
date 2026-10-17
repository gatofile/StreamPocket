package app.streampocket

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.OpenableColumns
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadStorageTest {
    @Test fun removedEntriesStayHiddenAfterTheHistoryIsReloaded() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val removed = UUID.randomUUID()
        val other = UUID.randomUUID()
        DownloadHistory(context).hide(removed)
        val reloaded = DownloadHistory(context).removed()
        assertTrue(removed.toString() in reloaded)
        assertFalse(other.toString() in reloaded)
    }
    @Test fun fileDeletionTargetsTheStoredMediaUri() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val provider = RecordingProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val uri = "content://media/external/audio/media/42"
        DownloadRemoval.deleteFile(context, uri)
        assertEquals(listOf(Uri.parse(uri)), provider.deleted)
    }
    @Test fun aStorageFailureDoesNotRemoveTheDownloadEntry() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val provider = RecordingProvider().apply { deletionResult = 0 }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        var removed = false
        assertThrows(java.io.IOException::class.java) {
            DownloadRemoval.run(true, "content://media/external/video/media/42",
                delete = { DownloadRemoval.deleteFile(context, it) }, removeEntry = { removed = true })
        }
        assertFalse(removed)
    }
    @Test fun anAbsentFileIsReportedAsMissingAndTheEntryIsRemoved() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val provider = RecordingProvider().apply { exists = false }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        var removed = false
        val missing = DownloadRemoval.run(true, "content://media/external/audio/media/42",
            delete = { DownloadRemoval.deleteFile(context, it) }, removeEntry = { removed = true })
        assertTrue(missing)
        assertTrue(removed)
        assertTrue(provider.deleted.isEmpty())
    }
    @Test fun accessDenialIsNotMisreportedAsAMissingFile() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val provider = RecordingProvider().apply { denied = true }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        var removed = false
        assertThrows(SecurityException::class.java) {
            DownloadRemoval.run(true, "content://media/external/audio/media/42",
                delete = { DownloadRemoval.deleteFile(context, it) }, removeEntry = { removed = true })
        }
        assertFalse(removed)
    }
    @Test fun removingAnAlreadyDeletedDownloadShowsOnlyTheMissingFileNotice() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val provider = RecordingProvider().apply { exists = false }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val job = androidx.work.WorkInfo(UUID.randomUUID(), androidx.work.WorkInfo.State.SUCCEEDED, setOf("download", "format:MP3"),
            outputData = androidx.work.workDataOf("uri" to "content://media/external/audio/media/42"))
        val vm = AppViewModel(context)
        vm.removeDownload(job, true)
        val deadline = System.currentTimeMillis() + 5000
        while (vm.notice == null && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("File not found", vm.notice)
        assertNull(vm.error)
        assertTrue(job.id.toString() in DownloadHistory(context).removed())
    }
    @Test fun aStaleMediaStoreRowDoesNotMakeAMissingFileLookPresent() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val provider = RecordingProvider().apply { physicalFile = false }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        var removed = false
        val missing = DownloadRemoval.run(true, "content://media/external/audio/media/42",
            delete = { DownloadRemoval.deleteFile(context, it) }, removeEntry = { removed = true })
        assertTrue(missing)
        assertTrue(removed)
        assertTrue(provider.deleted.isEmpty())
    }
    @Test fun bulkRemovalKeepsFilesCancelsSelectedWorkAndPreservesUnrelatedWork() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val manager = androidx.work.WorkManager.getInstance(context)
        val active = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>().setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .addTag("download").addTag("format:MP3").build()
        val unrelated = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>().setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
        manager.enqueue(listOf(active, unrelated)).result.get()
        val spec = File(context.filesDir, "jobs/${active.id}.json").apply { parentFile!!.mkdirs(); writeText("saved request") }
        val provider = RecordingProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = Format.entries.map { format -> androidx.work.WorkInfo(UUID.randomUUID(), androidx.work.WorkInfo.State.SUCCEEDED,
            setOf("download", "format:${format.name}"), outputData = androidx.work.workDataOf("uri" to "content://media/external/${format.name}/42")) }
        val vm = AppViewModel(context)
        vm.removeAllDownloads(saved + manager.getWorkInfoById(active.id).get()!!, false)
        awaitRemoval(vm)
        assertEquals((saved.map { it.id.toString() } + active.id.toString()).toSet(), DownloadHistory(context).removed())
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, manager.getWorkInfoById(active.id).get()!!.state)
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, manager.getWorkInfoById(unrelated.id).get()!!.state)
        assertFalse(spec.exists())
        assertTrue(provider.deleted.isEmpty())
        assertEquals("Downloads removed", vm.notice)
        assertNull(vm.error)
    }
    @Test fun bulkFileRemovalContinuesAfterDenialAndHandlesAlreadyMissingFiles() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val denied = RecordingProvider().apply { this.denied = true }
        val missing = RecordingProvider().apply { exists = false }
        val saved = RecordingProvider()
        listOf("denied.files" to denied, "missing.files" to missing, "saved.files" to saved).forEach { (authority, provider) ->
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
        }
        val jobs = listOf("denied.files", "missing.files", "saved.files").map { authority -> androidx.work.WorkInfo(UUID.randomUUID(),
            androidx.work.WorkInfo.State.SUCCEEDED, setOf("download", "format:MP4"),
            outputData = androidx.work.workDataOf("uri" to "content://$authority/file/42")) }
        val vm = AppViewModel(context)
        vm.removeAllDownloads(jobs, true)
        awaitRemoval(vm)
        assertEquals(jobs.drop(1).map { it.id.toString() }.toSet(), DownloadHistory(context).removed())
        assertEquals(listOf(Uri.parse("content://saved.files/file/42")), saved.deleted)
        assertTrue(denied.deleted.isEmpty()); assertTrue(missing.deleted.isEmpty())
        assertEquals("File not found", vm.notice)
        assertEquals("Couldn't remove 1 download. Check folder access and try again.", vm.error)
    }
    private fun awaitRemoval(vm: AppViewModel) {
        val deadline = System.currentTimeMillis() + 5000
        while (vm.removingDownloads.isNotEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(vm.removingDownloads.isEmpty())
    }
    private class RecordingProvider : ContentProvider() {
        val deleted = mutableListOf<Uri>()
        var deletionResult = 1
        var exists = true
        var denied = false
        var physicalFile = true
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/mpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (denied) throw SecurityException("Access denied")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { if (exists) addRow(arrayOf("song.mp3")) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (!physicalFile) throw java.io.FileNotFoundException("File not found")
            val file = File(context!!.cacheDir, "saved-song.mp3").apply { writeText("test media") }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted += uri; return deletionResult }
    }
}
