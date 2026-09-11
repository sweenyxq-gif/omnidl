package com.omnidownloader

import com.omnidownloader.download.http.RangeCalculator
import com.omnidownloader.download.http.SegmentMerger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class MockWebServerRangeTest {
    @Test fun rangeSegmentsCanBeDownloadedAndMerged() {
        val payload = ByteArray(32_777) { (it % 251).toByte() }; val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty()) ?: return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload))
            val start = match.groupValues[1].toInt(); val end = match.groupValues[2].toInt()
            return MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes $start-$end/${payload.size}").setBody(okio.Buffer().write(payload, start, end - start + 1))
        } }; server.start()
        val dir = createTempDirectory("omni-range").toFile()
        try {
            val client = OkHttpClient(); val files = RangeCalculator.calculate(payload.size.toLong(), 8).map { range ->
                val response = client.newCall(Request.Builder().url(server.url("/file")).header("Range", "bytes=${range.start}-${range.endInclusive}").build()).execute()
                check(response.code == 206); File(dir, range.index.toString()).apply { outputStream().use { response.body!!.byteStream().copyTo(it) }; response.close() }
            }
            val merged = ByteArrayOutputStream(); SegmentMerger.merge(files, merged); assertArrayEquals(payload, merged.toByteArray())
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
