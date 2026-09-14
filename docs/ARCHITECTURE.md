# Architecture

## Boundaries

- `presentation`: stateless Compose screens plus `MainViewModel`
- `domain`: protocol-neutral task, source, progress, errors, engine and repository contracts
- `data`: Room persistence, OkHttp inspection, DataStore settings, and SAF output
- `data/userscript`: persistent resolver registry, metadata matching, isolated JavaScript execution, and network policy enforcement
- `download/core`: source detection, engine routing, queue scheduling, retry/state policies
- `download/http`: segmented Range implementation, filename parsing, merge, and checksum
- `download/resolver`: a modular resolver contract and safe direct-link implementation
- `service`: foreground lifetime, connectivity observation, notifications, boot recovery
- `di`: process-scoped Hilt graph

## Durable HTTP state machine

```text
WAITING → RESOLVING → DOWNLOADING → COMPLETED
   │           │          │
   └───────────┴──────────┼→ PAUSED → WAITING
                          ├→ FAILED → WAITING
                          └→ CANCELLED
```

Each segment has an inclusive byte range, a disk path, persisted byte count, and completion bit. On restart, segment bytes are reused only when the range layout and the persisted strong ETag or Last-Modified validator match the current server probe. Legacy or changed resources restart safely. Resumed requests carry `If-Range`, and every 206 response must begin at the exact requested offset. A completed set is streamed in order into a hidden SAF part document while SHA-256 is computed. The document is renamed only after successful merge and optional checksum verification.

The initial segment count is bounded by file size, the user's connection ceiling, Range support, and whether the negotiated protocol is multiplexed. Runtime throughput-based growth/shrink remains a later optimization; the current policy deliberately avoids excessive connections for small files and HTTP/2.

## Queue and lifecycle

`DownloadQueueManager` combines Room state, DataStore settings, and validated network capabilities. It selects tasks by priority then creation time and holds a bounded set of engine jobs. Network loss pauses active jobs; reconnection makes eligible waiting tasks runnable. The foreground service owns user-visible lifetime and persistent notifications. WorkManager only normalizes interrupted state after boot.

`PerformancePolicy` applies one set of limits across engines. Eco mode or Android battery saver reduces the queue to one task, caps an HTTP transfer at two connections, and lengthens progress intervals. Only one torrent is scheduled at a time so the process never holds multiple native libtorrent sessions. Room progress writes and foreground-notification refreshes are rate-limited, completed engine progress flows are released, and recovery removes staging directories that no longer belong to a database task.

## Future engines

New engines implement `DownloadEngine` and are registered in `DownloadEngineRouter`. They must expose the same persisted task lifecycle, must not claim support until functional, and may introduce protocol-specific tables such as the existing reserved `torrent_state` table. Media manifests must be checked for DRM before offline operations.

## Database migrations

Room schema export is enabled. Version 1 is the baseline; version 2 adds resolved URL and remote validators; version 3 persists speed and ETA for UI and notification recovery. Destructive migration is forbidden; each schema change must include a checked-in migration and migration test.

## Userscript trust boundary

Userscripts are resolver plugins, not page automation. Installation requires a visible review of URL match rules, API grants, and network hosts. Enabled scripts run in a non-navigating WebView with file/content access and DOM storage disabled. Network calls pass through an OkHttp sandbox that permits only HTTP(S), enforces `@connect` for redirects, rejects local/private addresses, limits response size and time, and strips attempts to set `Host`. The only output is a list of candidate URLs returned by `omni.resolve()`; selecting and starting a download remains an explicit user action.

`ExtensionCatalog` contains optional, source-reviewed resolvers shipped with the application but not activated until the user reviews and installs them. Catalog extensions use the same parser, persistence and sandbox as imported scripts; there is no privileged catalog execution path. The Developer screen likewise executes test code through the production sandbox.

## Application updates

`UpdateCheckWorker` performs an inexact, network-constrained daily check of the repository's latest GitHub Release. `GitHubUpdateRepository` accepts only newer semantic versions and official GitHub asset URLs, preferring the device ABI before the universal APK. A user action starts Android `DownloadManager`; `UpdateDownloadReceiver` streams the downloaded file through SHA-256 and exposes it through a narrow FileProvider path only after the digest matches GitHub's release asset metadata. Android then independently enforces package name and signing-certificate continuity and requests installation approval. Release signing keys exist only in GitHub Actions secrets and are never stored in the repository.
