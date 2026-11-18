# LazyAria Browser

Lightweight Android browser. Rust core via JNI, WebView rendering.

**Package:** `aria.sparkynox.lazyaria`  
**Min SDK:** 24 (Android 7.0)

## Features

- Multi-tab browsing (ViewPager2)
- Address bar with history autocomplete
- Ad-block (hosts-based, Rust side — zero Java overhead)
- Bookmarks with folders (SQLite via Rust)
- Full history with search + pagination
- Downloads (Android DownloadManager + UI)
- Reader mode (Readability.js inject)
- Night mode (CSS invert inject)
- Incognito tabs (no history, no cookies)
- Desktop mode (custom UA)
- Find in page
- Text size control (50–200%)
- Custom CSS + JS injection per page
- Share URL
- Print page
- WebRTC disable (IP leak prevention)
- Cookie/cache/history clear
- DNT header
- DNS over HTTPS (DoH) setting
- Custom UA preset or free-text
- 7 search engines
- Swipe gesture → menu
- Long-press back/forward → navigation history
- Context menu on links/images (open in new tab, download, copy)
- Full-screen video
- Error pages (custom)
- Pull-to-refresh
- Per-build signed release APK via GitHub Actions

## Build locally

```bash
# Install Rust targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk

# Build Rust .so files
cd rust-core
cargo ndk -t aarch64-linux-android -o ../app/src/main/jniLibs build --release
cargo ndk -t armv7-linux-androideabi -o ../app/src/main/jniLibs build --release
cargo ndk -t x86_64-linux-android   -o ../app/src/main/jniLibs build --release

# Build APK
cd ..
./gradlew assembleDebug
```

APK at: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Push to `main` or `dev` → debug APK  
Trigger `workflow_dispatch` with `build_release: true` → signed release APK

### Release signing secrets (optional)

| Secret | Value |
|---|---|
| `KEYSTORE_FILE` | base64-encoded `.jks` keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

```bash
# Generate keystore
keytool -genkeypair -v -keystore lazyaria.jks -alias lazyaria -keyalg RSA -keysize 2048 -validity 10000

# Encode for GitHub secret
base64 -w 0 lazyaria.jks
```

## Adding blocklist

Put a hosts-format file at `app/src/main/assets/blocklist.txt`.  
Recommended: https://github.com/StevenBlack/hosts (download the `hosts` file)

## Project structure

```
├── rust-core/          Rust JNI library
│   └── src/lib.rs      URL norm, ad-block, SQLite bookmarks/history
├── app/src/main/
│   ├── java/.../
│   │   ├── core/       RustCore bridge, Prefs singleton
│   │   ├── browser/    LazyWebView, TabManager, Downloader
│   │   └── ui/         MainActivity, Settings, Bookmarks, History, Downloads, Tabs
│   └── res/            Layouts, colors, strings, prefs XML
└── .github/workflows/  Rust cross-compile → Android APK pipeline
```
