# FreeFile

[中文](README.md) | [English](README_EN.md) | [MIT License](LICENSE.md)

<p align="center">
  <img src="icon.png" width="128" alt="FreeFile icon">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="minSdk 24">
  <img src="https://img.shields.io/badge/version-v1.0-blue" alt="Version v1.0">
  <img src="https://img.shields.io/badge/license-MIT-yellow" alt="MIT License">
</p>

> An all-in-one Android dual-pane file manager built with Jetpack Compose, combining local, Root, and Shizuku access with network storage, terminal sessions, scripting, archive handling, media and document viewers, file encryption, and an AI file-management Agent.

## Overview

FreeFile is built around a **dual-pane layout** with **tabbed browsing**, aimed at advanced users who need efficient file operations. A unified virtual file system (VFS) abstraction lets the same file-operation logic work with local storage, Root Shell, Shizuku, archive contents, and remote locations such as SMB, FTP, WebDAV, and S3.

The app is written in Kotlin with Jetpack Compose and follows an MVI architecture. Beyond the core file manager, it provides a terminal, scripting, media viewing, PDF and web viewing, file encryption, local-network transfer, and a file-management Agent that can connect to any OpenAI-compatible endpoint.

## Features

### File management

- **Dual-pane layout**: independent browsing in each pane, one-tap swapping, optional right-pane visibility, and an active pane determined by touch interaction
- **Multiple tabs**: several directory tabs per pane with persisted sessions
- **Complete file operations**: create (including nested paths such as `a/b/c`), copy, cut, paste, rename, and batch rename (sequence / find-and-replace / prefix and suffix with live preview)
- **Recycle bin**: deletions go to the recycle bin first, with restore, empty, and permanent-delete options
- **Selection system**: select all, invert selection, long-press range selection, and a batch action panel
- **Properties**: directory statistics and persisted MD5 / SHA-256 checksums
- **Navigation aids**: breadcrumbs, path jump, bookmarks, recent directories, recent files, and a fixed `..` entry in every listing
- **Three view modes**: list, grid, and large icons; sorting by name, size, type, or modification time in either direction
- **Fine-grained filters**: folders, images, videos, audio, archives, documents, code, APKs, and a hidden-file toggle

### Access engines and permissions

- **Automatic engine selection** through [`EngineSelector`](app/src/main/java/com/freefile/vfs/EngineSelector.kt): path-based probing with **Root > Shizuku > local storage** priority, plus a cached path-prefix-to-engine mapping
- Manual modes: automatic, Root-first, Shizuku-first, and local-only
- Centralized management of Shizuku authorization and all-files access (`MANAGE_EXTERNAL_STORAGE`) in Settings
- Root and Shizuku are optional enhancements and do not affect local file management

### Search

- Configurable recursion depth (current directory only / specified depth / unlimited)
- Wildcards (`*` and `?`), regular expressions, exact matching, and case sensitivity
- Filters for file size, modification time, and file contents
- **Search index cache**: background indexing enables instant search and can be toggled or rebuilt in Settings

### Archives

- Supported formats: `zip`, `jar`, `apk`, `7z`, `tar`, `tar.gz`, `tgz`, `tar.bz2`, `tar.xz`, `rar4`, `rar5`, `gz`, `bz2`, and `xz`
- Encrypted ZIP extraction; ZIP compression supports passwords, compression levels, and split volumes (ZIP only)
- **Direct archive browsing**: the `xxx.zip#/internal/path` syntax maps internal archive directories without extracting them first
- Conflict strategies for copy, move, and extraction: overwrite, skip, or keep both, applicable in bulk

### Network and remote file systems

- **WebDAV, FTP, SMB2/3, and S3** (including S3-compatible endpoints such as Alibaba Cloud OSS), using the unified `remote://` path syntax
- Saved and editable network locations with SSL, domain, bucket, and custom endpoint settings
- **Local-network transfer**: NanoHTTPD plus mDNS (jmdns) for automatic device discovery and file exchange without mobile data, with a received-file history

### Built-in terminal

- A **real PTY + VT100** terminal based on the Termux terminal-emulator library, with no NDK build required
- Multiple terminal sessions, normal or Root mode, and custom interpreter paths (for example, `/system/bin/bash`)
- Accepts external Intent paths, automatically runs `cd` to the target directory, and lists the selected files
- A separate task stack that can be switched freely with the file interface

### Scripting

- Built-in **Lua** engine (LuaJ, pure Java) plus support for invoking a device-side **Python** interpreter (for example, in Termux)
- File API bindings such as `freefile.list / read / write / copy / delete / mkdir / rename / exists / log`
- Built-in scripts for listing directories, storage information, large-file search, batch-rename preview, directory-size statistics, and empty-directory cleanup

### Editors and viewers

- **Text editor**: search, replace, replace all, regular expressions, whole-word matching, case sensitivity, line numbers, jump to line, font-size adjustment, multiple encodings, and a large-file mode (files over 1 MB are truncated on load)
- **Rich-text editor**: based on richeditor-compose, with HTML / Markdown / plain-text editing, format conversion, and hyperlink insertion
- **Media viewer**: unified image, video, and audio browsing
  - Images: pinch zoom, double-tap zoom, pan, rotation, and set as wallpaper
  - Video: ExoPlayer playback, progress and fullscreen, 0.5x–2x speed, single-item loop, audio and subtitle track selection, and Picture-in-Picture
  - Audio: custom playback controls, previous/next track, artist, and album information
  - Details: resolution, duration, EXIF capture time, camera, location, artist, and album
- **PDF viewer**: vertical scrolling, double-tap zoom, zoom in/out, page jump, sharing, and file details
- **Web viewer**: multi-tab browsing, a drawer for tab management, address-bar search, back/forward/refresh, downloads with live progress, browser settings (JavaScript, storage, zoom, fonts, user agent, proxy, mixed content, and more), certificate-error confirmation, and an eruda debugging console

### File encryption

- **AES-256-GCM** file encryption independent of archives (`.cry` format), with PBKDF2-HMAC-SHA256 key derivation (200,000 iterations and a random salt)
- Encryption and decryption entries in Open With, with password-strength hints, password confirmation, and an option to delete the source after encryption
- A wrong password or corrupted file never produces plaintext
- Note: a lost password cannot be recovered

### Tool tray and APK tools

- **Tool tray**: bundled APK tools (Acode and APKToolM) with a three-step extract, install, and launch flow, and the ability to hand the current file directly to a tool
- **APK launcher**: resolves the package name, entry Activity, and version of an uninstalled APK, with fully customizable launch Intents: action, categories, data, extras (String/Int/Bool/Long), and window flags
- Supports downloading and updating tools from a remote catalog; see [example/tools.json](example/tools.json) for the index format

### File-management Agent (AI)

- **Bring your own endpoint**: OpenAI-compatible protocol with presets for OpenAI, DeepSeek, Moonshot Kimi, and Qwen (DashScope compatible mode), plus custom base URL and model
- **Tool calling**: 30+ local file tools covering search, listing, file information, recycle bin, create, rename, move, copy, delete to trash, restore, compress, extract, read/write text, storage analysis, duplicate scanning, batch rename, tags, sharing, and media-library queries
- **MCP support**: configurable external MCP servers (Streamable HTTP / JSON-RPC 2.0) that bridge remote tools to the model
- **Write-operation safety**: every write operation shows a preview confirmation card by default, with selection, conflict policy, batch threshold, and danger warnings; selected tools can be marked "always allow" in Settings, and completed operations can be undone in one tap
- **Operation log**: local records of write operations and undo status, viewable and clearable
- **Privacy controls**: only file metadata is sent by default; reading file contents requires separate authorization; no built-in or proxied API keys
- Three modes (Auto / File / General), streaming replies, cancellation, a tool-round limit, and a hideable drawer entry

### Document center and localization

- **Gallery document center**: bundled user agreement, privacy policy, help, and about pages, switchable between Chinese and English
- **Localization**: Simplified Chinese and English with instant in-app switching (per-app language)

### Other

- **Unified Open With** ([OpenWithActivity](app/src/main/java/com/freefile/ui/OpenWithActivity.kt)): share, open with a system app, text editor, tool tray, terminal, compress/extract, encrypt/decrypt, media/PDF/web viewers, with results passed back to the main screen for context handling
- File-size calculation is disabled by default to speed up large directories and can be enabled in Settings
- Transfer, compression, and extraction tasks are managed by the application-level `TransferManager` coroutines, with live progress and cancellation

## Technology stack

| Area | Technologies |
| --- | --- |
| Language and UI | Kotlin 1.9.24, Jetpack Compose, Material 3 |
| Architecture | MVI, ViewModel, StateFlow, coroutines |
| Persistence | Room (tabs, bookmarks, history, recycle bin, Agent conversations) and DataStore (preferences) |
| Navigation | Navigation Compose and separate Activity task stacks |
| Privileged access | Shizuku API 13.1.5 and Root Shell |
| Archives | zip4j, Apache Commons Compress, XZ, and JunRAR |
| Networking | OkHttp, commons-net (FTP), SMBJ (SMB2/3), NanoHTTPD, and jmdns |
| Terminal | Termux terminal-emulator and terminal-view v0.118 |
| Scripting | LuaJ 3.0.1 |
| Editors | richeditor-compose |
| Media | Coil (GIF / WebP / video frames), Media3 ExoPlayer, and ExifInterface |
| Documents and web | AndroidPdfViewer, the system WebView, and a bundled eruda console |
| AI Agent | OkHttp SSE, OpenAI-compatible Chat Completions, MCP (JSON-RPC 2.0), and org.json |
| Encryption | `javax.crypto` AES-256-GCM / PBKDF2-HMAC-SHA256 |
| Testing | JUnit4 and AndroidX Test |

## Requirements

| Item | Requirement |
| --- | --- |
| minSdk | 24 (Android 7.0) |
| targetSdk | 33 |
| compileSdk | 34 |
| JDK | 17 |
| Gradle | 8.0 (included through the Gradle Wrapper) |
| Android Gradle Plugin | 8.1.2 |
| NDK / CMake | Not required |

Root and Shizuku are optional enhancements.

## Build

Open the project in Android Studio, or run:

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Run local unit tests:

```bash
gradlew.bat :app:testDebugUnitTest
```

Notes:

- The terminal emulator, PDF viewer, and similar components are consumed as prebuilt AAR or Maven dependencies; **no local NDK or CMake installation is required**
- The Termux terminal components are resolved from JitPack, so the first build needs access to `jitpack.io`
- The `release` build has no signing configuration yet; add a `signingConfig` in `app/build.gradle.kts` before publishing
- Make sure the Android SDK path in `local.properties` is correct, or let Android Studio generate it

## First run

1. Grant all-files access when prompted (required on Android 11 and later)
2. To access system directories, install and authorize Shizuku, or use Root mode on a rooted device
3. Configure language, search depth, file-size calculation, and the search index in Settings
4. For AI features, enter the endpoint, model, and API key in Agent Settings, then run a connection test
5. For NAS or cloud storage, add a WebDAV, FTP, SMB, or S3 location under Network Locations in the drawer

### Custom tool catalog

To use the third-party tool download feature, replace the constant in [DownloadToolActivity](app/src/main/java/com/freefile/ui/DownloadToolActivity.kt) with your own catalog URL:

```kotlin
private const val TOOL_CATALOG_URL = "https://your-domain.example/json/tools.json"
```

The catalog JSON format is shown in [example/tools.json](example/tools.json). `downloadUrl` points to the APK download address; `https://thisisexample.com/example` in the sample is a placeholder.

## Project structure

```text
app/src/main/java/com/freefile/
├── FreeFileApp.kt / MainActivity.kt   # App entry points
├── core/            # MVI base classes and permission management
├── data/            # Room database, repositories, DataStore settings, external tool index
├── operations/      # File operations, transfer management, extraction, conflict policies
├── vfs/             # Virtual file system: Local / Shell / Archive / Remote engines and selector
│   └── remote/      # WebDAV / FTP / SMB / S3 clients
├── search/          # Search engine and index
├── terminal/        # Termux terminal session management
├── shell/           # Root / Shizuku shell providers
├── script/          # Lua / Python script engine
├── share/           # Local-network transfer: HTTP service and mDNS discovery
├── plugin/          # Plugin management
├── security/        # File encryption and decryption (AES-256-GCM / .cry)
├── tools/           # Agent, media, PDF, web, rich text, and compression engines
└── ui/              # Compose UI: panes, dialogs, settings, terminal, and standalone Activities
```

## Permissions

| Permission | Purpose |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | Core file browsing; must be granted manually in system settings |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | Legacy storage access on Android 9 and earlier |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | Android 13+ media permissions for media browsing and queries |
| `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` | Tool-tray package resolution and APK installation |
| Shizuku permission | Privileged file access when Root is unavailable |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` / `NEARBY_WIFI_DEVICES` | Remote file systems and local-network transfer, including mDNS discovery |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | Declared for long-running tasks and notifications; the current version mainly uses foreground coroutines |

## Privacy and security

- **No telemetry**: the app includes no analytics SDK and does not upload usage data
- **AI data boundary**: conversations go to the endpoint you configure. Only file metadata such as name, path, and size is sent by default; reading and sending file contents requires separate authorization in Agent Settings. API keys stay on the device
- **API key storage**: keys currently use local DataStore storage (not a system-encrypted container). Avoid storing sensitive keys on shared devices; a future version may upgrade to encrypted storage
- **Local-network transfer**: uses private-network cleartext HTTP by default; enable it only on trusted networks
- **Root / Shizuku**: privileged operations can modify system files and damage a device; use them carefully
- **File encryption**: a `.cry` file cannot be recovered if its password is lost
- **Bundled sample APKs**: Acode and APKToolM under `assets/apk/` are sample tools and remain the property of their authors. Replace or remove them for your own distribution and comply with their licenses
- **Third-party components**: some dependencies (such as the Termux terminal-emulator) use GPL-family licenses; follow their upstream terms when distributing. Confirm the licensing of third-party AARs under `app/libs/` before redistribution

## Known limitations and roadmap

- Password protection and split volumes apply only to ZIP; 7z compression does not support encryption yet, and TAR formats have no encryption capability
- Agent write operations currently target the local file system; remote locations and inside-archive paths are mainly read-only
- Name conflicts offer overwrite, skip, and keep-both policies; the app has no file version history, so overwritten files cannot be recovered
- Media playback has no background MediaSession or notification controls; video supports Picture-in-Picture
- Long-running tasks rely on foreground coroutines; a foreground service and resumable transfers are not implemented yet
- Unit-test coverage is limited; real-device regression is still required (fresh install, cold start, language switching, orientation changes, low memory, and large directories)
- The `release` build is unsigned and the repository has no CI configured

Planned work:

- Expand write operations for remote locations and archive contents
- Add more viewers and tool capabilities
- Add automated tests and continuous integration
- Continue optimizing large directories, very large files, and low-memory scenarios

## Contributing

Issues and pull requests are welcome.

- Search existing issues first, and include device model, system version, reproduction steps, and relevant logs
- Before submitting code, run `gradlew.bat :app:assembleDebug` and `gradlew.bat :app:testDebugUnitTest`
- Add user-visible strings to both `values/strings.xml` and `values-en/strings.xml`
- Respect the existing package boundaries and MVI structure, and avoid starting long-running tasks in a Compose composition scope
- Never commit real API keys, signing files, personal paths, or logs containing sensitive information

## Third-party components and licenses

The original code in this project is licensed under the [MIT License](LICENSE.md). Third-party dependencies, sample APKs, and AARs remain under their respective licenses, including:

- Kotlin, AndroidX, Jetpack Compose, and Material 3
- Shizuku (Rikka)
- zip4j, Apache Commons Compress, XZ, and JunRAR
- OkHttp, commons-net, SMBJ, NanoHTTPD, and jmdns
- Termux terminal-emulator and terminal-view
- LuaJ and richeditor-compose
- Coil, Media3 ExoPlayer, and AndroidPdfViewer
- eruda (web debugging console)

Refer to each dependency's upstream repository and release notes for exact versions and licenses.

## Disclaimer

- The app provides Root / Shizuku privileged file operations; use them only with full awareness of the risks. Deleting or moving system files can damage a device
- Local-network transfer uses unencrypted private-network HTTP by default; do not enable it on public networks
- AI Agent file facts come from local tool results, but model output can still be wrong; verify previews before executing write operations
- The tools in the app are intended for learning, personal file processing, and analysis of applications you own; comply with applicable laws and software licenses
