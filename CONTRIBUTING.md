# Contributing

1. Open an issue describing the behavior and security implications.
2. Keep protocol code behind `DownloadEngine`; keep Android UI concerns out of engines.
3. Add unit tests for parsing, ranges, scheduling, retry, transitions, merge, and integrity logic. Network behavior should use MockWebServer.
4. Run `./gradlew testDebugUnitTest assembleDebug` before submitting.
5. Never log Cookie, Authorization, proxy credentials, passwords, or signed query strings.

Features intended to bypass DRM, authentication, payment, geographic policy, or other access controls will not be accepted.
