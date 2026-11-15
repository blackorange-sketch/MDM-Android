# MDM-Android

Android port of [Mister Download Manager](https://github.com/anefzaoui/misterdownloadmanager):
multi-connection downloads with resume, plus torrents and magnet links.

The download engine (`mdm-engine`) and the torrent session (`mdm-torrent`, librqbit) are used
from upstream as they are. This repository adds:

- `core/` — a Rust `cdylib` with a small JNI bridge (two calls, JSON in and out).
- `android/` — the app: Kotlin + Jetpack Compose, a foreground service, links from other apps
  (magnet, `.torrent`, Share → MDM).

Licensed under GPL-3.0-or-later, like upstream.

## Build

GitHub Actions builds everything on each push: the Rust core with `cargo ndk` for `arm64-v8a`,
then the debug APK (artifact `MDM-debug-apk`).

Locally: install the Android NDK and `cargo install cargo-ndk`, then

```sh
cargo ndk --platform 26 -t arm64-v8a -o android/app/src/main/jniLibs build --release -p mdm-android-core
cd android && gradle assembleDebug
```

## Status

First skeleton, not yet run on a device.

- Files go to the app's own Downloads folder (`Android/data/dev.blackorange.mdm/files/Download`).
  Moving finished files to the public Downloads (MediaStore) is not done yet.
- Left out of the port: the browser-like connection (BoringSSL), yt-dlp / ffmpeg video downloads,
  the browser extension, CLI and MCP server.
- TLS uses Android's certificate store through `rustls-platform-verifier`.
