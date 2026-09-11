package com.omnidownloader

import com.omnidownloader.domain.model.DownloadSource
import com.omnidownloader.download.core.SourceDetector
import org.junit.Assert.*
import org.junit.Test

class SourceDetectorTest {
    private val detector = SourceDetector()
    @Test fun detectsSupportedInputs() {
        assertTrue(detector.detect("https://example.com/file.zip") is DownloadSource.Http)
        assertTrue(detector.detect("magnet:?xt=urn:btih:abc") is DownloadSource.Magnet)
        assertTrue(detector.detect("https://example.com/live.m3u8") is DownloadSource.Hls)
        assertTrue(detector.detect("https://example.com/manifest", "application/dash+xml") is DownloadSource.Dash)
        assertTrue(detector.detect("ftp://example.com/file") is DownloadSource.Ftp)
        assertTrue(detector.detect("sftp://example.com/file") is DownloadSource.Sftp)
        assertNull(detector.detect("javascript:alert(1)"))
    }
}
