# FreeFile

[中文](README.md) | [English](README_EN.md) | [License](LICENSE.md)

> An all-in-one Android dual-pane file manager built with Jetpack Compose, supporting local, Root, Shizuku, network storage, terminal sessions, scripting, archives, and reverse-engineering tools.

## Overview

FreeFile is designed for efficient file operations with a **dual-pane layout** and **tabbed browsing**. A unified virtual file system (VFS) abstraction lets the same file-operation logic work with local storage, Root Shell, Shizuku, archive contents, and remote locations such as SMB, FTP, WebDAV, and S3.

## Features

### File management

- Dual-pane browsing with pane swapping and optional right-pane visibility
- Multiple persistent directory tabs in each pane
- Create, copy, cut, paste, rename, and batch rename files and directories
- Recycle bin with restore, empty, and permanent-delete options
- Select all, invert selection, range selection, and batch actions
- Directory statistics and persistent MD5 / SHA-256 checksums
- Breadcrumbs, path navigation, bookmarks, recent directories, and recent files
- List, grid, and large-icon views with sorting and fine-grained filters

### Access and permissions

- Automatic engine selection through [`EngineSelector`](app/src/main/java/com/freefile/vfs/EngineSelector.kt), prioritizing Root, Shizuku, and local storage
- Manual engine modes: automatic, Root-first, Shizuku-first, and local-only
- Centralized management for Shizuku and all-files-access permissions

### Search and archives

- Configurable recursion depth, wildcards, regular expressions, exact matching, and case sensitivity
- Filters for file size, modification time, and file contents
- Optional background search index for fast searches
- Support for ZIP, JAR, APK, 7z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, RAR4, RAR5, GZ, BZ2, and XZ
- Encrypted ZIP extraction and direct browsing of archive paths such as `example.zip#/internal/path`
- Conflict strategies for copy, move, and extraction: overwrite, skip, or keep both

### Network and remote file systems

- WebDAV, FTP, SMB2/3, and S3-compatible endpoints, including services such as Alibaba Cloud OSS
- Saved network locations with SSL, domain, bucket, and custom endpoint settings
- Local-network file transfer using NanoHTTPD and mDNS device discovery

### Terminal and scripting

- PTY and VT100 terminal based on the Termux terminal-emulator library
- Multiple terminal sessions, normal or Root mode, and custom interpreter paths
- External Intent path handling with automatic `cd` and directory listing
- Lua scripting through LuaJ and optional Python interpreter integration
- Built-in APIs for listing, reading, writing, copying, deleting, creating directories, renaming, checking existence, and logging

### Editors and tools

- Text editor with search, replacement, regular expressions, line numbers, encoding options, and large-file mode
- Rich-text editor supporting HTML, Markdown, and plain text conversion
- APK tool tray with extraction, installation, launching, and configurable launch Intents
- VirtualBox-style APK execution through the Bcore virtual application container
- Unified open/share actions for system apps, the editor, terminal, tools, and extraction

The application supports Simplified Chinese and English, with instant in-app language switching.

## Technology stack

| Area | Technologies |
| --- | --- |
| Language and UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | MVI, ViewModel, StateFlow, coroutines |
| Persistence | Room and DataStore |
| Navigation | Navigation Compose |
| Privileged access | Shizuku API 13.1.5, Root Shell |
| Archives | zip4j, Apache Commons Compress, XZ, JunRAR |
| Networking | OkHttp, commons-net, SMBJ, NanoHTTPD, jmdns |
| Terminal and scripts | Termux terminal-emulator, terminal-view 0.118, LuaJ 3.0.1 |

## Requirements

- Android 7.0 or later (`minSdk 24`)
- `targetSdk 33`, `compileSdk 34`
- JDK 17 and Gradle 8.0 (included through the Gradle Wrapper)
- Root and Shizuku are optional enhancements
- No local NDK or CMake installation is required

## Build

Open the project in Android Studio, or run:

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

If you want to use the "third-party app download" feature in an application you build yourself, replace the constant

java
TOOL_CATALOG_URL = "https://thisisexample.com/json/tools.json";
in DownloadToolActivity with your own tools index JSON. For the format, see /example/tools.json.

Here, https://thisisexample.com/example is an example link, and example is the file example.apk with the .apk suffix removed.
## Permissions and safety

The application may request all-files access, package-query and APK-install permissions, network/Wi-Fi permissions, Shizuku access, and foreground-service permission. These permissions are used for file management, remote file systems, local-network transfer, APK tools, and long-running transfer tasks.

Root and Shizuku operations can modify system files and may damage a device. Local-network transfer uses unencrypted private-network HTTP by default. Use these features only on trusted networks. Reverse-engineering tools are intended for learning, personal file processing, and analysis of applications you own; comply with applicable laws and software licenses.

## Project status and documentation

The current version is **v1.0**. Core features are implemented and the project builds successfully; archive creation and virtual-container Provider integration are still evolving. See the [development progress](PROGRESS.md) for details.

- [中文 README](README.md)
- [Development progress](PROGRESS.md)
- [MIT License](LICENSE.md)

## Disclaimer

This project is provided on an "AS IS" basis. You are responsible for permissions, files, devices, network environments, and third-party software used with the application.
