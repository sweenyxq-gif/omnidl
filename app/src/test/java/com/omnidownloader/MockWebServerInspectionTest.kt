package com.omnidownloader

import com.omnidownloader.data.network.HttpInspector
import com.omnidownloader.download.core.SourceDetector
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class MockWebServerInspectionTest {
    @Test fun readsMetadataAndRangeSupport() = runTest {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1024").addHeader("Accept-Ranges", "bytes").addHeader("Content-Disposition", "attachment; filename=\"archive.zip\"")); server.start()
        try { val result = HttpInspector(OkHttpClient(), SourceDetector()).inspect(server.url("/asset").toString()); assertEquals("archive.zip", result.fileName); assertEquals(1024, result.size); assertTrue(result.supportsRanges) } finally { server.shutdown() }
    }

    @Test fun fallsBackToRangedGetWhenHeadOmitsFilename() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "HEAD") {
                MockResponse().setResponseCode(200).addHeader("Content-Type", "application/octet-stream")
            } else {
                assertEquals("bytes=0-0", request.getHeader("Range"))
                MockResponse().setResponseCode(206)
                    .addHeader("Content-Disposition", "attachment; filename*=UTF-8''real%20name.zip")
                    .addHeader("Content-Type", "application/zip")
                    .addHeader("Content-Range", "bytes 0-0/4096")
                    .setBody("x")
            }
        }
        server.start()
        try {
            val result = HttpInspector(OkHttpClient(), SourceDetector()).inspect(server.url("/download?id=7").toString())
            assertEquals("real name.zip", result.fileName)
            assertEquals(4096, result.size)
            assertTrue(result.supportsRanges)
        } finally { server.shutdown() }
    }
}
