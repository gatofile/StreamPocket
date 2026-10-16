# Third-party components

StreamPocket source: GPL-3.0-or-later.

- [youtubedl-android](https://github.com/yausername/youtubedl-android/tree/0.18.1), 0.18.1: GPL-3.0. Includes Android wrappers, Python distribution, yt-dlp, QuickJS, FFmpeg, and their bundled components. See upstream LICENSE, build recipes, and component notices for the exact bundled builds.
- [yt-dlp nightly 2026.10.10.233141](https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/2026.10.10.233141): Unlicense; bundled dependencies have their own licenses. Official Python ZIP executable bundled separately; SHA-256: `f40b6a9e4c5786360953911cab5d2f87507626b8fb7bfee3f8f1c2e5cf341fae`. This executable also includes the upstream EJS component.
- [FFmpeg](https://ffmpeg.org/legal.html): LGPL/GPL depending on enabled components; the bundled build comes from youtubedl-android. Review its build configuration before redistribution.
- [AndroidX / Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/): Apache-2.0.
- [Coil](https://github.com/coil-kt/coil): Apache-2.0.
- [Kotlin](https://github.com/JetBrains/kotlin): Apache-2.0.
- [Robolectric](https://github.com/robolectric/robolectric): MIT, test-only.
- [JUnit 4](https://github.com/junit-team/junit4): EPL-1.0, test-only.

This is a development handoff, not a completed distribution-license audit. Preserve upstream notices and make corresponding GPL source, including component build scripts, available when distributing a release. YouTube and YouTube Music are trademarks of their respective owners; StreamPocket is unaffiliated.
