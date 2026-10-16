# StreamPocket for Android

A native Android app for finding YouTube videos and saving MP3 audio or MP4 video on your phone. Android 10 or newer. No server or API key is required.

Version 0.4.2 separates MP3 and MP4 downloads, gives Donate a light blue coffee-button style, and shows the engine's Update button only when an update is available. The compact interface, Show in folder, and missing-file handling remain included. Install this APK over the previous version; settings and downloads do not need to be cleared.

## Install

Download **StreamPocket.apk** from the [latest release](https://github.com/gatofile/StreamPocket/releases/latest). This single universal installer includes ARM and Intel processor support. Transfer it to your Android phone, open it, and allow installation from that source when Android asks. Android 10 or newer is required. This is a development build, signed with a development key; it is not a Play Store release.

## Use

1. Paste a YouTube or YouTube Music video URL, or search by song, artist, or video name. You can also share a video from YouTube to StreamPocket.
2. Open a result. The review opens immediately; metadata loads in the background while you choose the format, quality, or folder. Existing edits are preserved as richer metadata arrives. The app checks up to three likely song releases in the background, stopping at the first confident match. Matching does not hold up the Download button after metadata loads. If you download before matching finishes, the currently displayed version is used.
3. Choose MP3 or MP4 and the desired quality. The Settings defaults are preselected.
4. Review the metadata, or tap Edit metadata to change it. Supported fields: title, artist, album, album artist, year, track number, genre, and cover image URL. An empty cover URL removes artwork. Use an HTTPS URL for replacement artwork.
5. Review or change the folder on this screen, then tap Download. Saving starts directly with no extra folder-confirmation popup.
6. See progress in Downloads, with separate MP3 and MP4 tabs for audio and video. Queuing a download opens its matching tab. Failed or cancelled items can be reviewed and retried. Completed files have Open and Show in folder buttons. The latter opens the original save folder in a compatible file manager; if none is available, it opens Android's file browser at that folder. Old downloads' saved destination details are supported too.
7. Swipe a download to the left to reveal the red trash-can button. Tap it to remove the entry. For a saved download, the dialog asks whether to also delete its MP3 or MP4 file from the phone; this is unchecked by default. An already-deleted file produces a brief “File not found” message and the entry is removed. Permission or storage failures still keep the entry available. Removing an unfinished download cancels it and keeps any file that already finished saving.

Settings saves independent MP3 and MP4 quality and folder defaults. The default locations are `Music/StreamPocket` and `Movies/StreamPocket`. A custom folder uses Android's system picker; select or create a subfolder if Android disallows selecting a storage root. The chosen folder must grant persistent write access.

The **Donate on Ko-fi** button in Settings opens [ko-fi.com/gatofile](https://ko-fi.com/gatofile) in the browser. Donations are completed on Ko-fi.

## Behavior and limitations

- MP3 choices are encoding bitrates from 64 to 320 kbps. Increasing the bitrate does not improve the source recording.
- MP4 choices are resolution limits from 144p to 2160p. The app uses the best available MP4 within the limit, preferring H.264 video and AAC audio. It reports a failure if no compatible format is available; it does not exceed the selected resolution.
- Metadata comes from the selected YouTube item. Missing album details remain blank instead of being invented. A song release's artwork is used when a song match is selected; an ordinary video's cover may be its video thumbnail.
- Song matching is conservative: it requires matching artist/title information and evidence of an official release. Remixes, live versions, covers, and very short snippets are rejected. There is no guarantee every YouTube Music release will be found. If no confident match is found, the review screen says so and keeps the original.
- Search and extraction depend on YouTube availability. Private, restricted, paid, DRM-protected, live, or sign-in-required videos are not supported. There is no cookies or account-import feature.
- Version 0.2.0 bundles official yt-dlp nightly **2026.10.10.233141**, verified against the release's SHA-256 checksum. It replaces the old November 2025 engine on first use, including when upgrading an existing installation.
- Settings checks the download engine automatically when opened. **Update** appears only when an update is available; tap it and confirm to install, or Cancel to leave it for later. A current engine says **Up to date**. Use the refresh icon to check again manually. A failed check says it could not check, instead of claiming the engine is current. Checking fetches release information without downloading the engine binary. Installation validates the official checksum and embedded version, activates it atomically, and runs `--version` before reporting success. A failed activation restores the previous engine. The engine version is displayed in Settings.
- A stream that returns HTTP 403 is retried once with newly extracted stream URLs. Numeric year/track fields are stored as numbers, while empty numeric fields are omitted; edited tags remain intact on retry. Persistent access restrictions are reported as errors.
- Downloads use Android WorkManager and foreground notifications. Android may defer work or stop long-running downloads; error details and manual retry are available. Download history follows WorkManager retention and is not a permanent media library.
- Download only content you own or have permission to download. No login credentials, search history, or media are uploaded to an app-owned server. Requests go to YouTube, media/artwork hosts, and the upstream update service.

## License

This project is provided under GPL-3.0-or-later because it incorporates GPL components through youtubedl-android. See `LICENSE` and `THIRD_PARTY.md`. Preserve the corresponding source and license notices if distributing builds.
