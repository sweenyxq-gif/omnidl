# Changelog

## Unreleased

## 0.5.2 - 2026-09-15

- Added a repeatable desktop contract harness covering all 18 compatible resolver extensions.
- Replaced obsolete GitHub and GitLab HTML scraping with their official release APIs after live-link testing exposed both failures.
- Verified live resolution against public Civitai, GitHub, GitLab, Hugging Face, Internet Archive, SourceForge, and HTML5 media links.
- Added the complete extension contract suite to the signed GitHub release workflow.

## 0.5.1 - 2026-09-15

- Reworked the extension lifecycle so packaged resolvers remain current across app upgrades while preserving enable/disable choices.
- Require explicit Omni Resolver API compatibility before a script can be installed, tested, or activated.
- Excluded the incompatible browser-only video userscript from the active resolver catalog.
- Made extension updates identity-safe and preserve enabled and bundled state.
- Isolated resolver failures so one broken extension cannot prevent other matching extensions from returning links.
- Added a short result-settling window so multi-file resolvers retain every emitted link.
- Restricted remote extension installation to HTTPS and capped fetched scripts and catalogs at 1 MB.
- Added installed/enabled/bundled/update health counts, search, status filters, and expandable permission details to the Extensions UI.
- Added regression coverage for all 18 bundled Omni resolvers, incompatible scripts, and safe extension replacement.

## 0.5.0 - 2026-09-14

- Rebuilt OmniDL around a compact graphite-and-cyan transfer-manager design with a reusable Compose component system.
- Consolidated primary navigation into Downloads, Discover, Extensions, and Settings, with a navigation rail on tablets.
- Added dense status-aware download rows, host search, stable transfer metrics, improved empty states, and download details/action sheets.
- Added a clipboard-aware Add Download sheet and preserved direct download, inspection, resolver, torrent, extension, update, and settings flows.
- Added contextual notification permission requests and reliable share/deep-link delivery while the activity is already open.
- Fixed Wi-Fi-only transfers continuing after mobile fallback and added automatic recovery of network-paused transfers.
- Hardened SAF writes, legacy database/settings decoding, sensitive backup exclusions, and destructive-action confirmations.
- Added configurable retry attempts and expanded queue policy coverage.
- Removed the obsolete exported update-download receiver.
- Hardened the GitHub release workflow to require signing, validate tag/version agreement, and publish SHA-256 checksums.

- Added validator-safe HTTP resume with persisted ETag/Last-Modified metadata, `If-Range`, and Content-Range validation.
- Added size- and protocol-aware initial segment counts instead of always using the configured maximum.
- Added duplicate task prevention for the same source and destination.
- Added and tested the non-destructive Room 1→2 migration.
- Added persistent, smoothed transfer speed and ETA in download cards and notifications.
- Added pre-download torrent metadata discovery for magnets and `.torrent` files, including name, size, hash, trackers, and file listing.
- Simplified torrent setup to reuse the saved destination and metadata-derived filename automatically.
- Added persistent resolver userscripts with file/paste import, explicit permission review, enable/disable, uninstall, and startup restoration.
- Hardened userscript networking by enforcing `@connect` on redirects, rejecting private-network targets, limiting supported grants, and filtering unsafe resolved URL schemes.
- Added Eco mode and automatic Android battery-saver adaptation for queue concurrency, HTTP connections, and progress polling.
- Reduced Room and notification update frequency, limited native torrent concurrency to one session, bounded userscript output, and cleaned orphaned staging files during recovery.
- Bounded OkHttp request/socket pools and shortened idle connection retention to reduce background memory and radio usage.
- Added signed GitHub Releases delivery with daily WorkManager checks, in-app manual checks, ABI-aware assets, verified downloads, update notifications, and Android installer handoff.
- Added a tag-driven GitHub Actions release workflow and environment-based release signing configuration.
- Redesigned the main shell, download overview, navigation, settings groups, and extension experience for a more consistent Material interface.
- Added Installed, Discover, and Developer extension sections plus curated GitLab Releases, Internet Archive, and SourceForge resolvers.
- Reduced settings writes by committing slider values only after drag completion.

## 0.1.1 - 2026-09-11

- Fixed an immediate startup crash caused by incompatible Compose UI and Lifecycle Compose versions.
- Added a Robolectric/Hilt fresh-install activity launch regression test.

## 0.1.0 - 2026-09-11

- Initial Android 10+ Compose application.
- Added Room-backed unified queue, HTTP probing, segmented Range transfers, pause/resume/cancel, retries, checksum verification, SAF output, foreground execution, notifications, share integration, settings, recovery, and tests.
- Added disabled navigation surfaces and explicit roadmap for torrent, media, browser, resolver, FTP/SFTP, and proxy phases.
## 0.2.0

- Added native libtorrent downloads for magnet links and `.torrent` files on all Android ABIs.
- Added swipeable status pages, search, count badges, focused navigation, redesigned cards/forms, and confirmed deletion.
- Added multi-file torrent export to Storage Access Framework folders and share/open intent support.
