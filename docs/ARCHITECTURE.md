# Architecture

## Boundaries

- `presentation`: stateless Compose screens plus `MainViewModel`
- `domain`: protocol-neutral task, source, progress, errors, engine and repository contracts
- `data`: Room persistence, OkHttp inspection, DataStore settings, and SAF output
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

Each segment has an inclusive byte range, a disk path, persisted byte count, and completion bit. On restart the file length is treated as the source of truth only when the persisted range layout still matches the current probe. Incompatible layouts are discarded. A completed set is streamed in order into a hidden SAF part document while SHA-256 is computed. The document is renamed only after successful merge and optional checksum verification.

## Queue and lifecycle

`DownloadQueueManager` combines Room state, DataStore settings, and validated network capabilities. It selects tasks by priority then creation time and holds a bounded set of engine jobs. Network loss pauses active jobs; reconnection makes eligible waiting tasks runnable. The foreground service owns user-visible lifetime and persistent notifications. WorkManager only normalizes interrupted state after boot.

## Future engines

New engines implement `DownloadEngine` and are registered in `DownloadEngineRouter`. They must expose the same persisted task lifecycle, must not claim support until functional, and may introduce protocol-specific tables such as the existing reserved `torrent_state` table. Media manifests must be checked for DRM before offline operations.

## Database migrations

Room schema export is enabled. Version 1 is the baseline. Destructive migration is forbidden; each schema change must include a checked-in migration and migration test.
