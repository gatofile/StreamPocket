package app.streampocket

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamRecoveryTest {
    @Test fun forbiddenStreamIsRetriedWithFreshInformationExactlyOnce() {
        val prepares = mutableListOf<Boolean>()
        var attempts = 0
        var refreshed = false
        val result = StreamRecovery.run(prepare = { prepares += it }, download = {
            attempts++
            if (attempts == 1) throw IOException("HTTP Error 403: Forbidden")
            "saved"
        }, onRefresh = { refreshed = true })
        assertEquals("saved", result)
        assertEquals(listOf(false, true), prepares)
        assertEquals(2, attempts)
        assertTrue(refreshed)
    }
    @Test fun persistentForbiddenResponseDoesNotLoop() {
        var attempts = 0
        assertThrows(IOException::class.java) {
            StreamRecovery.run(prepare = {}, download = { attempts++; throw IOException("HTTP Error 403: Forbidden") })
        }
        assertEquals(2, attempts)
    }
    @Test fun unrelatedErrorsAndCancellationDoNotRetry() {
        var attempts = 0
        assertThrows(IOException::class.java) {
            StreamRecovery.run(prepare = {}, download = { attempts++; throw IOException("HTTP Error 404: Not Found") })
        }
        assertEquals(1, attempts)
        attempts = 0
        assertThrows(IOException::class.java) {
            StreamRecovery.run(prepare = {}, download = { attempts++; throw IOException("HTTP Error 403: Forbidden") }, cancelled = { true })
        }
        assertEquals(1, attempts)
    }
    @Test fun refreshedInformationStillGetsTheUsersMetadataEdits() {
        val seen = mutableListOf<String>()
        val tags = Tags("User title", "User artist", "User album", "", "2001", "", "3", "")
        var attempts = 0
        StreamRecovery.run(prepare = {
            val raw = org.json.JSONObject().put("artist", if (it) "Fresh artist" else "Cached artist")
            Metadata.apply(raw, tags, "https://www.youtube.com/watch?v=fo0iOZGlMpE")
            seen += raw.getString("meta_artist")
            assertTrue(raw.get("track_number") is Number)
        }, download = { if (attempts++ == 0) throw IOException("HTTP Error 403: Forbidden") })
        assertEquals(listOf("User artist", "User artist"), seen)
    }
}
