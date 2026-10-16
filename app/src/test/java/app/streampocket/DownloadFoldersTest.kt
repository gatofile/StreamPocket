package app.streampocket

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFoldersTest {
    @Test fun defaultAudioAndVideoFoldersAreDifferentAndExact() {
        assertEquals("primary:Music/StreamPocket", DocumentsContract.getDocumentId(DownloadFolders.defaultFolder(Format.MP3)))
        assertEquals("primary:Movies/StreamPocket", DocumentsContract.getDocumentId(DownloadFolders.defaultFolder(Format.MP4)))
    }
    @Test fun customFoldersRetainTheirProviderAndTreeGrant() {
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.documents", "music-folder")
        val folder = DownloadFolders.documentUri(tree.toString())
        assertEquals("com.example.documents", folder.authority)
        assertEquals("music-folder", DocumentsContract.getDocumentId(folder))
        assertEquals("music-folder", DocumentsContract.getTreeDocumentId(folder))
    }
    @Test fun aFolderViewUsesADirectoryMimeTypeWithoutUnownedDefaultFolderGrants() {
        val folder = DownloadFolders.defaultFolder(Format.MP3)
        val intent = DownloadFolders.viewIntent(folder)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(folder, intent.data)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, intent.type)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Test fun aPhoneWithoutAFolderViewerGetsTheSystemFileBrowserAtThatFolder() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intents = mutableListOf<Intent>()
        val wrapper = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                intents += intent
                if (intents.size == 1) throw ActivityNotFoundException()
            }
        }
        val folder = DownloadFolders.defaultFolder(Format.MP4)
        DownloadFolders.open(wrapper, folder)
        assertEquals(2, intents.size)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intents.last().action)
        assertEquals(folder, intents.last().getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
    }
}
