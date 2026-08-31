# ☁️ CloudVault for Android

> **Turn your Telegram into a free, unlimited, high-speed personal cloud vault on Android.**

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Engine-TDLib-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" />
  <img src="https://img.shields.io/badge/UI-Material_You-008080?style=for-the-badge" />
</p>

---

## ✨ Features

### 🎬 High-Performance Video Streaming
* **Instant Playback**: Stream large 4K / 1080p MKV, MP4, and WebM videos directly from Telegram Cloud without waiting for full downloads.
* **LibVLC Engine**: Hardware acceleration, multi-track audio selection, embedded subtitle support, playback speed control (0.5x – 2.0x), and aspect ratio adjustments.
* **Multi-Part & ZIP Streaming**: Stream split archive files seamlessly as a single continuous video.

### 📖 Built-in Document & PDF Reader
* **Horizontal Page Swiping**: Smooth `ViewPager2` book-style horizontal swipe navigation.
* **Pinch-to-Zoom & Pan**: Two-finger pinch-to-zoom (up to 6x) and double-tap zoom for crisp viewing of documents.
* **Orientation Toggle**: Switch between horizontal page swiping and continuous vertical scrolling.
* **Fast Navigation**: Floating page pill with direct jump-to-page dialog.
* **In-App Previews**: Preview EPUBs, Markdown, and source code files directly.

### 🎵 Background Audio Player & Mini-Player
* **Floating Mini-Player**: Bottom mini-player bar with progress tracking and quick playback controls.
* **Queue & Playlist Management**: Up-next queue sheet, repeat, and shuffle modes.
* **Lock Screen & MediaSession Controls**: Full notification controls with album artwork extraction from Telegram and embedded ID3 tags.
* **Sleep Timer & Speed**: Built-in sleep timer and audio pitch/speed customization.

### 🖼️ Smart Media Gallery
* **Dynamic Grid Zoom**: Instant pinch-to-zoom and toggle between **Day**, **Month**, and **Year** timeline views.
* **Interactive Fullscreen Viewer**: High-definition image viewer with pinch-to-zoom, pan, rotation, and swipe dismissal.
* **Fast Scroller**: Smooth draggable vertical fast scroller for navigating thousands of items effortlessly.

### 🔄 Auto-Backup & Sync
* **Background Camera Backup**: Automatically backs up new camera photos and videos to your private Telegram Saved Messages.
* **Folder Selection**: Choose specific device folders to monitor and backup.
* **Network & Charging Rules**: Option to sync only on Wi-Fi or while charging.

### 🔍 Duplicate Media Finder
* **Vault Deduplication**: Scan your vault by file checksums and dimensions to identify duplicate files and reclaim space in 1 tap.

### 📤 Android System Share Target
* **Direct Backup**: Share photos, videos, or documents directly from your device Gallery, Files app, or Browser into CloudVault via the native Android Share sheet.

### 🎨 9 Premium Themes & Material You
* **Material You / Monet**: Auto-adapts accent colors to your device wallpaper on Android 12+.
* **AMOLED Pure Black**: True `#000000` pitch black for maximum battery savings.
* **Midnight Neon Purple**: Cyberpunk dark background with neon violet accents.
* **Emerald Forest Green**: Deep dark forest background with mint & emerald accents.
* **Sunset Amber Gold**: Warm espresso dark background with glowing amber accents.
* **Dracula Crimson Rose**: Dark velvet background with ruby rose highlights.
* **Ocean Sapphire Blue**: Deep navy background with royal sapphire accents.
* **Light Mode & Obsidian Dark**: Classic high-contrast daylight and deep slate modes.

---

## 🚀 Getting Started

### Prerequisites
* Android device running **Android 8.0 (Oreo / API 26)** or higher.
* Telegram account.

### Setup
1. **Download APK**: Grab the latest release APK from the [Releases](https://github.com/deepu2135/CloudVaultAndroid/releases) tab.
2. **API Credentials**: (Optional / Built-in) You can use your own `api_id` and `api_hash` from [my.telegram.org](https://my.telegram.org).
3. **Log In**: Enter your phone number and verify via the official Telegram login code.
4. **Enjoy Unlimited Cloud**: All media uploaded is stored securely in your private Telegram Cloud.

---

## 🛠️ Architecture & Tech Stack

* **Language**: [Kotlin](https://kotlinlang.org/) (100%)
* **Cloud & Networking**: [TDLib (Telegram Database Library)](https://core.telegram.org/tdlib) via native JNI
* **Media Playback**: [LibVLC Android](https://code.videolan.org/videolan/vlc-android) & Android `MediaPlayer`
* **Concurrency**: Kotlin Coroutines & Reactive `StateFlow` / `SharedFlow`
* **Local Caching**: Memory `LruCache` + disk cache management with auto-cleanup
* **UI Components**: Material Components Android, `ViewPager2`, Custom Touch Views

---

## 🔒 Privacy & Security

* **Zero Intermediary Servers**: CloudVault communicates directly with Telegram's official servers via TDLib.
* **No Telemetry / Tracking**: Your files, login sessions, and metadata remain strictly between your device and Telegram Cloud.
* **Cloud Encryption**: Inherits Telegram's MTProto cloud encryption.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
