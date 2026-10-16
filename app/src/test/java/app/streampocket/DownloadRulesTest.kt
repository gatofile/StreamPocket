package app.streampocket

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    private val original = Media("original", "https://www.youtube.com/watch?v=abcdefghijk", "Artist - Song (Official Video)",
        "ArtistVEVO", 300, track = "Song", artist = "Artist")
    private val song = original.copy(id = "song", title = "Song", channel = "Artist - Topic", duration = 240, album = "Album")
    @Test fun recognizesYoutubeLinksWithoutAcceptingLookalikeHosts() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", Input.videoUrl("https://youtu.be/abcdefghijk?list=x"))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", Input.videoUrl("https://music.youtube.com/watch?v=abcdefghijk&list=y"))
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", Input.videoUrl("https://www.youtube.com/shorts/abcdefghijk"))
        assertNull(Input.videoUrl("https://youtube.com.evil.test/watch?v=abcdefghijk"))
        assertNull(Input.videoUrl("https://youtube.com@evil.test/watch?v=abcdefghijk"))
        assertNull(Input.videoUrl("file:///etc/passwd"))
        assertNull(Input.videoUrl("https://youtube.com/playlist?list=abc"))
    }
    @Test fun songMatchRequiresArtistTrackAndOfficialReleaseEvidence() {
        assertEquals(song, MusicMatcher.choose(original, listOf(song)))
        assertNull(MusicMatcher.choose(original, listOf(song.copy(artist = "Someone Else"))))
        assertNull(MusicMatcher.choose(original, listOf(song.copy(channel = "Fan Channel", description = ""))))
        assertNull(MusicMatcher.choose(original, listOf(song.copy(track = "Song remix"))))
        assertNull(MusicMatcher.choose(original, listOf(song.copy(duration = 45))))
        assertNull(MusicMatcher.choose(original, listOf(song.copy(track = "Unrelated Song"))))
    }
    @Test fun mp4NeverFallsBackToUncappedOrNonMp4Video() {
        val selector = DownloadOptions.forFormat(Format.MP4, 720).first().second!!
        selector.split('/').forEach { branch -> assertTrue(branch.contains("height<=720")); assertTrue(branch.contains("ext=mp4")) }
    }
    @Test fun audioSpecUsesEncodingBitrate() {
        assertTrue(DownloadOptions.forFormat(Format.MP3, 192).contains("--audio-quality" to "192K"))
    }
    @Test fun specificationsKeepEditsAcrossRestarts() {
        val tags = Tags.from(song).copy(title = "My Title", album = "Edited Album", cover = "https://example.com/cover.jpg")
        val spec = DownloadSpec(song, tags, Format.MP3, 256, "content://folder/tree/music")
        assertEquals(spec, DownloadSpec.read(spec.json()))
    }
    @Test fun generatedFileNamesStayInsideDestinationFolder() {
        assertEquals("_.._escape", Input.fileName("../../escape"))
        assertEquals("download", Input.fileName("..."))
        assertFalse(Input.fileName("Artist: Song/Title").contains('/'))
    }
    @Test fun metadataEditsAndRemovalOverrideSourceValues() {
        val raw = org.json.JSONObject().put("artist", "Old Artist").put("webpage_url", original.url).put("thumbnail", "old-cover")
        val tags = Tags.from(song).copy(artist = "Edited Artist", album = "", cover = "", year = "2001")
        Metadata.apply(raw, tags, original.url)
        assertEquals("Edited Artist", raw.getString("meta_artist"))
        assertEquals("", raw.getString("meta_album"))
        assertEquals("2001", raw.getString("meta_date"))
        assertEquals(0, raw.getJSONArray("thumbnails").length())
        assertEquals(original.url, raw.getString("meta_purl"))
        assertFalse(raw.has("webpage_url"))
    }
    @Test fun numericMetadataUsesNumbersAndEmptyFieldsAreOmitted() {
        val raw = org.json.JSONObject()
        val tags = Tags.from(song).copy(year = "2001", track = "3/12")
        Metadata.apply(raw, tags, song.url)
        assertTrue(raw.get("release_year") is Number)
        assertTrue(raw.get("track_number") is Number)
        assertEquals(2001, raw.getInt("release_year"))
        assertEquals(3, raw.getInt("track_number"))
        assertEquals("3/12", raw.getString("meta_track"))
        Metadata.apply(raw, tags.copy(year = "", track = ""), song.url)
        assertFalse(raw.has("release_year"))
        assertFalse(raw.has("track_number"))
    }
    @Test fun downloadErrorsShowTheActualFailureInsteadOfTheAgeWarning() {
        val e = java.io.IOException("WARNING: Your yt-dlp version is older than 90 days!\nWARNING: track_number is not numeric\nERROR: unable to download video data: HTTP Error 403: Forbidden")
        assertEquals("ERROR: unable to download video data: HTTP Error 403: Forbidden", downloadError(e))
    }
}
