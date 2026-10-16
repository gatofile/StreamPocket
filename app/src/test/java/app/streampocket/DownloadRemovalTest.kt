package app.streampocket

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadRemovalTest {
    @Test fun removingOnlyTheEntryNeverTouchesThePhoneFile() {
        var removed = false
        DownloadRemoval.run(false, "content://media/external/audio/media/42", delete = { fail("Must keep the file") }, removeEntry = { removed = true })
        assertTrue(removed)
    }
    @Test fun deletingTheFileTargetsItsExactUriBeforeRemovingTheEntry() {
        val actions = mutableListOf<String>()
        val uri = "content://media/external/video/media/42"
        DownloadRemoval.run(true, uri, delete = { actions += it }, removeEntry = { actions += "remove entry" })
        assertEquals(listOf(uri, "remove entry"), actions)
    }
    @Test fun fileDeletionFailureKeepsTheEntryAvailable() {
        var removed = false
        assertThrows(IOException::class.java) {
            DownloadRemoval.run(true, "content://media/external/audio/media/42", delete = { throw IOException("Access denied") }, removeEntry = { removed = true })
        }
        assertFalse(removed)
    }
    @Test fun anUnfinishedEntryDoesNotAttemptToDeleteAnUnknownFile() {
        var removed = false
        DownloadRemoval.run(true, null, delete = { fail("There is no file URI") }, removeEntry = { removed = true })
        assertTrue(removed)
    }
    @Test fun alreadyDeletedFilesStillAllowTheEntryToBeRemoved() {
        var removed = false
        val missing = DownloadRemoval.run(true, "content://media/external/audio/media/42",
            delete = { throw java.io.FileNotFoundException("File not found") }, removeEntry = { removed = true })
        assertTrue(missing)
        assertTrue(removed)
    }
}
