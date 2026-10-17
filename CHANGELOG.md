# 0.5.0

- Check GitHub's latest public release at launch and prompt when a newer app version is available.
- Put App update first in Settings, with manual checks, current-version status, and download progress.
- Download updates only after confirmation, verify their checksum, package, version, and signing key, then open Android's installer.
- Handle installation permission, cancelled downloads, failed checks, and retries without clearing app data.

# 0.4.2

- Check the download engine when Settings opens. Show Update only after confirming an update is available; show Up to date otherwise, with a refresh icon for manual checks.
- Keep the available update visible after cancelling its confirmation, and remove Update after successful installation.
- Restyle Donate on Ko-fi in light blue with a coffee icon and external-link indicator.
- Separate Downloads into MP3 and MP4 tabs, including active, saved, failed, and cancelled items. Open the matching tab when a new download is queued.

# 0.4.1

- Make Donate on Ko-fi a full-width coral button with a larger heart icon.
- Check for download-engine updates before offering installation. Show a short up-to-date message or an Update/Cancel dialog.
- Avoid downloading the engine binary during the update check. Keep official release checksums, runtime verification, and rollback during installation.
- Keep check and installation failures separate, with concise messages and a Retry button.

# 0.4.0

- Replace the promotional layout with a compact Android interface: direct search, plain download rows, quality menus, and concise settings.
- Remove slogans, decorative feature cards, numbered steps, large format cards, and repeated helper text.
- Show a brief “File not found” message and remove the download entry when its saved file is already gone. Recognize stale Android media-index rows too; keep permission failures separate.
- Add Show in folder to completed downloads, supporting default folders, custom document-provider folders, and saved destination details from older versions.
- Fall back to Android's file browser at the destination when no directory-viewing app is available.

# 0.3.0

- Swipe a download left to reveal a red trash-can icon.
- Ask whether to also delete the saved MP3 or MP4 file; keep-file is the default.
- Persist entry removals across app restarts without removing unrelated downloads.
- Cancel unfinished downloads when removing them. Keep already-saved files unless the user explicitly selects file deletion.
- Delete saved files through their exact Android content URI, including custom document-provider folders. Keep the entry visible if deletion fails.
- Add a Donate on Ko-fi button in Settings linking to https://ko-fi.com/gatofile.

# 0.2.1

- Fix engine-version detection for executable ZIP archives on Android. Read the ZIP after explicitly skipping its Python header instead of relying on the desktop Java ZipFile behavior.
- Keep the official release checksum and runtime-version verification checks.
- Add coverage for executable headers with either line ending and installation of the actual bundled engine asset.
- Preserve the package ID and signing key so this fix installs over previous versions.

# 0.2.0

- Open the review immediately on result selection or URL submission, then fill in metadata in the background.
- Preserve metadata edits, format, quality, and folder choices during background enrichment.
- Run song-release matching in the background, check fewer candidates, stop on the first confident match, and skip matching when an official Topic release is already selected.
- Reuse extraction results for up to two minutes instead of repeating extraction when Download is pressed.
- Remove the extra folder confirmation popup. Download uses the folder shown on the review screen.
- Bundle the official 2026.10.10.233141 nightly yt-dlp engine, replacing the old 2025.11.12 binary on first use.
- Verify updates against the official release checksum, inspect the actual installed version, run the installed engine before reporting success, and restore the previous engine if activation fails.
- Show the actual engine version in Settings.
- Store numeric year and track fields as numbers and omit empty numeric fields.
- Retry HTTP 403 streams once using freshly extracted information while preserving user metadata edits.
- Prioritize the actual error line in download diagnostics.

This update keeps the same application ID and signing key as 0.1.0, so it can be installed over the previous build.
