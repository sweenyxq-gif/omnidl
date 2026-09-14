# Omni Downloader

Omni Downloader is an Android 10+ download manager built with Kotlin and Jetpack Compose. The current `0.2.0` milestone provides restart-safe HTTP/HTTPS downloads plus a native libtorrent-backed BitTorrent engine.

## Features

- HTTP/HTTPS metadata inspection and filename/MIME detection
- Adaptive HTTP Range segmentation within a user-defined cap, with automatic single-stream fallback
- Pause, validator-safe resume using ETag/Last-Modified and If-Range, cancel, retry with bounded exponential backoff, and crash-safe segment state
- Streaming I/O, `Long` offsets, optional SHA-256 validation, and temporary SAF documents to protect final files
- Unified priority queue with three concurrent tasks by default
- Room-backed state/history and reboot/process recovery
- Foreground data-sync service, grouped persistent progress notification, and pause/cancel actions
- Storage Access Framework destinations; no broad storage permission
- Per-request headers, Cookie, User-Agent, Referer, Wi-Fi-only rule, share target, light/dark/system theme, and Material You colors
- TLS verification for HTTPS plus explicit cleartext HTTP support with an in-app security warning
- Magnet links and `.torrent` files through libtorrent, with DHT/peer discovery, progress, pause/resume/cancel, and multi-file SAF export
- Pre-download torrent metadata preview with automatic filename/size detection and remembered destination settings
- Persisted transfer speed and ETA in the download list and foreground notification
- Persistent resolver userscripts with `.user.js` file/paste import, permission review, enable/disable, uninstall, URL matching, and restricted `@connect` networking
- Eco mode and automatic battery-saver adaptation with bounded HTTP sockets, one active native torrent session, and throttled progress/notification writes
- Signed in-app updates from GitHub Releases with daily background checks, ABI-aware APK selection, SHA-256 verification, and Android installer confirmation
- Extension Center with installed-script controls, a curated resolver catalog, permission review, and a sandboxed developer playground
- Swipeable download status pages, queue search, status counts, and safer task deletion

## Screenshots

> Screenshots will be added after the first signed device build.

| Downloads | Add download | Settings |
|---|---|---|
| _Placeholder_ | _Placeholder_ | _Placeholder_ |

## Architecture

The project follows Clean Architecture/MVVM boundaries in one Gradle application module while the API settles. See [Architecture](docs/ARCHITECTURE.md).

```text
Compose UI → ViewModel → Repository / Queue → Engine router → HTTP engine
                         ↓                         ↓
                       Room              disk segments → SAF
```

Core packages mirror future module boundaries: `presentation`, `domain`, `data`, `download`, `service`, and `di`.

## Build

Requirements: JDK 17 and Android SDK Platform 35.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

The debug APK is emitted at `app/build/outputs/apk/debug/app-debug.apk`. Open the root directory in Android Studio for device execution. Select a destination directory through the system folder picker before starting a transfer.

## Publishing updates

Push a semantic version tag such as `v0.3.0` to run `.github/workflows/release.yml`. Configure these GitHub Actions secrets first: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The same keystore must be retained for every release because Android only accepts an update signed by the installed application's signing certificate.

The workflow publishes optimized ABI-specific and universal APKs to GitHub Releases. Installed builds check the latest public release once daily while connected and notify once per version. The user can also check immediately under Settings. An update is downloaded only after the user taps, is accepted only when GitHub supplies a SHA-256 asset digest, and still requires Android's installer approval. Android does not allow ordinary sideloaded applications to install updates silently.

## Supported Android versions

- Minimum: Android 10 / API 29
- Target and compile SDK: API 35

Foreground-service and notification behavior uses the modern data-sync service type and Android 13+ notification permission. Reboot recovery is a bounded WorkManager task; indefinite transfers run only in a foreground service.

## Download engines

| Engine | Status |
|---|---|
| Direct HTTP/HTTPS | Enabled in Phase 1 |
| Torrent / magnet | Enabled in Phase 2 |
| Non-DRM HLS / DASH | Phase 3, disabled |
| Sandboxed resolver userscripts | Enabled (safe API subset) |
| Provider resolvers, browser, FTP/SFTP, proxy | Phase 4, disabled |

## Security considerations

Only download material you are authorized to access. Omni Downloader does not bypass DRM, authentication, payments, or access controls. TLS certificate checks are never disabled for HTTPS. Cleartext HTTP is supported for legacy servers but is visibly marked as unencrypted; credentials and cookies should not be used with it. Remote filenames are sanitized, secrets are excluded from logs, destination access is restricted to user-granted SAF trees, and incomplete output is not exposed under its final filename.

Cookies and authorization headers are currently stored in the private Room database so interrupted transfers can resume. Device backup excludes that database. A future release should add Android Keystore-backed field encryption before a production store release.

## Known limitations

- Servers without Range support resume by safely restarting the single stream from byte zero.
- Some document providers do not support rename; finalization then fails without exposing a corrupt completed file.
- Torrent file-priority selection, category-specific directories, drag gestures for priority, global throttling, proxies, browser interception, provider-specific resolvers, and protocol engines after BitTorrent are not implemented yet.
- Userscripts intentionally support a resolver-only subset of the Tampermonkey APIs. They cannot modify webpages or start downloads, and `.omni` packages are not yet supported.
- Extension isolation still requires an independent production security audit before a public script catalog is enabled.

## Roadmap

1. Phase 1: robust direct HTTP engine and Android lifecycle integration (this milestone)
2. Phase 2: Android-compatible libtorrent binding, magnet/`.torrent` intake, resume-safe payloads, and torrent UI (current; per-file priorities remain)
3. Phase 3: Media3-backed offline non-DRM HLS/DASH with track selection
4. Phase 4: public provider resolvers, lightweight browser, FTP/SFTP, proxy refinements, category folders, and advanced scheduling

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please do not propose access-control or DRM bypass features.

## License

See [LICENSE](LICENSE). The license text is intentionally a placeholder until the project owner selects one.
