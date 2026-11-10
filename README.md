# Manga Reader

A simple Android manga and manhwa reader for PDF, CBZ and ZIP files, built on
top of the official [MuPDF](https://mupdf.com/) rendering library (not a fork).

Version: 1.7.0

## Download

**[Download the latest APK](https://github.com/reswanaadiwarga/manga-reader/releases/latest)**
(Android 8.0+)

Each release includes the APK and the corresponding source archives
(AGPL-3.0).

### Install

1. Open the latest release and download `MangaReader-<version>.apk`.
2. Allow installation from unknown apps if prompted.
3. Play Protect may warn because the APK is debug-signed and not distributed
   through Google Play; choose "Install anyway".

## Features

- Folder library via SAF: pick a root folder once; browse nested series folders
- Library-wide search by series or chapter name: case-insensitive, path-aware,
  all words in any order; results open directly and keep chapter navigation
- Continuous vertical scrolling, fitted to screen width
- Reads PDF, CBZ and ZIP; CBZ/ZIP pages use Android image decoding
  (WebP, JPEG, PNG, GIF, BMP), natural-sorted
- Immersive top bar: hidden while reading; a single tap shows/hides the chapter
  name and page counter
- Manual page rotation for sideways pages: current page, all pages, or reset
- Previous / Next chapter actions at the end of a chapter
- Remembers the last page per document; manual open resumes, chapter navigation
  starts at page 1
- Thin progress bars, finished checkmarks, a Continue card, and both
  per-chapter and per-folder resets
- "Last read" pill on the most recently opened chapter in each folder
- Reaching the last page marks a chapter finished automatically
- Pinch-to-zoom, pan, and double-tap 1x/2x, with resolution-aware re-rendering
- Right-edge drag scrollbar for fast navigation
- No INTERNET permission and no analytics; reading progress stays on-device

CBR/RAR, EPUB and TIFF inside archives are not supported.

## Build

Requirements: JDK 17+, Android SDK 36.

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

- minSdk 26 (Android 8.0+)
- MuPDF `com.artifex.mupdf:fitz:1.28.5` is resolved from
  `https://maven.ghostscript.com/`

## License

This project is licensed under the GNU Affero General Public License v3.0;
see [LICENSE](LICENSE).

It uses MuPDF, Copyright (c) Artifex Software, Inc., also licensed under
AGPL-3.0. MuPDF source is available from
https://github.com/ArtifexSoftware/mupdf.
