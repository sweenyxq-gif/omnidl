package com.omnidownloader

import com.omnidownloader.download.http.FileNameParser
import com.omnidownloader.download.http.AdaptiveConnectionPolicy
import com.omnidownloader.download.http.RemoteValidatorPolicy
import com.omnidownloader.download.http.RangeCalculator
import org.junit.Assert.*
import org.junit.Test

class HttpUtilitiesTest {
    @Test fun contentDispositionSupportsQuotedAndUtf8Names() {
        assertEquals("report.pdf", FileNameParser.fromContentDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("hello world.txt", FileNameParser.fromContentDisposition("attachment; filename*=UTF-8''hello%20world.txt"))
        assertEquals("€ rates.pdf", FileNameParser.fromContentDisposition("attachment; filename*=UTF-8'en'%E2%82%AC%20rates.pdf"))
        assertEquals("quarter;final.pdf", FileNameParser.fromContentDisposition("attachment; filename=\"quarter;final.pdf\""))
        assertEquals("preferred name.txt", FileNameParser.fromContentDisposition("attachment; filename=old.txt; filename*=UTF-8''preferred%20name.txt"))
    }
    @Test fun filenameIsSanitizedAgainstTraversal() {
        assertEquals("evil_.apk", FileNameParser.sanitize("../../evil?.apk"))
        assertNull(FileNameParser.sanitize(".."))
    }
    @Test fun rangesCoverEveryByteWithoutOverlap() {
        val ranges = RangeCalculator.calculate(4_294_967_311L, 8)
        assertEquals(0L, ranges.first().start)
        assertEquals(4_294_967_310L, ranges.last().endInclusive)
        assertEquals(4_294_967_311L, ranges.sumOf { it.length })
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.endInclusive + 1, b.start) }
    }
    @Test fun urlAndMimeProvideUsefulFallbackNames() {
        assertEquals("release notes.pdf", FileNameParser.fromUrl("https://example.com/files/release%20notes.pdf?token=abc"))
        assertEquals("manual.pdf", FileNameParser.resolve(null, "https://example.com/download?filename=manual", "application/pdf"))
        assertEquals("download.mp4", FileNameParser.resolve(null, "https://example.com/download?id=42", "video/mp4"))
    }
    @Test fun adaptiveConnectionsRespectSizeProtocolAndServer() {
        val mb = 1024L * 1024
        assertEquals(1, AdaptiveConnectionPolicy.initialConnections(100 * mb, 16, false, false))
        assertEquals(1, AdaptiveConnectionPolicy.initialConnections(1 * mb, 16, true, false))
        assertEquals(2, AdaptiveConnectionPolicy.initialConnections(8 * mb, 16, true, false))
        assertEquals(4, AdaptiveConnectionPolicy.initialConnections(512 * mb, 16, true, true))
        assertEquals(6, AdaptiveConnectionPolicy.initialConnections(512 * mb, 16, true, false))
        assertEquals(3, AdaptiveConnectionPolicy.initialConnections(2L * 1024 * mb, 3, true, false))
    }
    @Test fun remoteValidatorsPreventUnsafeSegmentReuse() {
        assertTrue(RemoteValidatorPolicy.canReuse("\"v1\"", null, "\"v1\"", null))
        assertFalse(RemoteValidatorPolicy.canReuse("\"v1\"", null, "\"v2\"", null))
        assertFalse(RemoteValidatorPolicy.canReuse(null, null, "\"v1\"", null))
        assertTrue(RemoteValidatorPolicy.canReuse(null, "Sun, 14 Sep 2026 10:00:00 GMT", null, "Sun, 14 Sep 2026 10:00:00 GMT"))
        assertEquals("\"strong\"", RemoteValidatorPolicy.ifRange("\"strong\"", "date"))
        assertEquals("date", RemoteValidatorPolicy.ifRange("W/\"weak\"", "date"))
    }
}
