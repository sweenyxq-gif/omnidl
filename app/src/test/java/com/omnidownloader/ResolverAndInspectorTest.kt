package com.omnidownloader

import com.omnidownloader.data.inspector.LinkInspector
import com.omnidownloader.data.resolver.DirectUrlResolver
import com.omnidownloader.data.resolver.GenericWebpageResolver
import com.omnidownloader.data.resolver.RedirectResolver
import com.omnidownloader.data.resolver.ResolverManager
import com.omnidownloader.data.userscript.UserscriptEngine
import com.omnidownloader.data.userscript.UserscriptResolver
import com.omnidownloader.data.userscript.UserscriptStorage
import com.omnidownloader.domain.model.SourceType
import com.omnidownloader.domain.resolver.ResolveRequest
import com.omnidownloader.domain.resolver.ResolveResult
import com.omnidownloader.domain.userscript.UserscriptExecutionResult
import com.omnidownloader.domain.userscript.UserscriptMetadata
import com.omnidownloader.download.core.SourceDetector
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolverAndInspectorTest {

    @Test
    fun directUrlResolverHandlesDirectFilesAndMagnets() = runTest {
        val resolver = DirectUrlResolver(SourceDetector())

        assertTrue(resolver.canHandle("https://example.com/files/archive.zip"))
        assertTrue(resolver.canHandle("magnet:?xt=urn:btih:abc1234567890"))
        assertFalse(resolver.canHandle("https://example.com/watch?v=123"))

        val zipRes = resolver.resolve(ResolveRequest("https://example.com/files/archive.zip"))
        assertTrue(zipRes is ResolveResult.Success)
        val zipItem = (zipRes as ResolveResult.Success).results.first()
        assertEquals("archive.zip", zipItem.filename)
        assertEquals(SourceType.HTTP, zipItem.type)

        val magnetRes = resolver.resolve(ResolveRequest("magnet:?xt=urn:btih:abc1234567890"))
        assertTrue(magnetRes is ResolveResult.Success)
        val magnetItem = (magnetRes as ResolveResult.Success).results.first()
        assertEquals(SourceType.MAGNET, magnetItem.type)
    }

    @Test
    fun redirectResolverFollowsHopsAndDetectsLoops() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/start" -> MockResponse().setResponseCode(302).addHeader("Location", "/step2")
                    "/step2" -> MockResponse().setResponseCode(302).addHeader("Location", "/final.zip")
                    "/final.zip" -> MockResponse().setResponseCode(200)
                        .addHeader("Content-Length", "2048")
                        .addHeader("Content-Type", "application/zip")
                    "/loopA" -> MockResponse().setResponseCode(302).addHeader("Location", "/loopB")
                    "/loopB" -> MockResponse().setResponseCode(302).addHeader("Location", "/loopA")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val redirectResolver = RedirectResolver(OkHttpClient())

            // Test successful redirect chain
            val res = redirectResolver.resolve(ResolveRequest(server.url("/start").toString()))
            assertTrue(res is ResolveResult.Success)
            val item = (res as ResolveResult.Success).results.first()
            assertTrue(item.url.endsWith("/final.zip"))
            assertEquals(2048L, item.size)

            // Test loop detection
            val loopRes = redirectResolver.resolve(ResolveRequest(server.url("/loopA").toString()))
            assertTrue(loopRes is ResolveResult.Failed)
            assertTrue((loopRes as ResolveResult.Failed).reason.contains("Resolver loop detected"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun genericWebpageResolverExtractsMediaCandidates() = runTest {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:video" content="https://cdn.example.com/trailer.mp4">
            </head>
            <body>
                <video src="/media/stream.mp4"></video>
                <audio src="/media/podcast.mp3"></audio>
                <a href="/downloads/setup.exe">Download Installer</a>
                <a href="magnet:?xt=urn:btih:0123456789abcdef">Torrent</a>
            </body>
            </html>
        """.trimIndent()

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(html)
        )
        server.start()

        try {
            val resolver = GenericWebpageResolver(OkHttpClient())
            val res = resolver.resolve(ResolveRequest(server.url("/page.html").toString()))

            assertTrue(res is ResolveResult.Success)
            val items = (res as ResolveResult.Success).results
            assertTrue(items.any { it.label == "OpenGraph Video" && it.url.contains("trailer.mp4") })
            assertTrue(items.any { it.label == "HTML5 Video" && it.url.endsWith("/media/stream.mp4") })
            assertTrue(items.any { it.label == "HTML5 Audio" && it.url.endsWith("/media/podcast.mp3") })
            assertTrue(items.any { it.type == SourceType.MAGNET })
            assertTrue(items.any { it.url.endsWith("/downloads/setup.exe") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun linkInspectorCapturesFullNetworkProbe() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/initial" -> MockResponse().setResponseCode(301).addHeader("Location", "/asset.iso")
                    "/asset.iso" -> MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", "application/x-iso9660-image")
                        .addHeader("Content-Length", "5242880")
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("ETag", "\"iso-tag-123\"")
                        .addHeader("Server", "nginx/1.24")
                        .addHeader("Set-Cookie", "auth_token=secret99; Path=/; HttpOnly")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val inspector = LinkInspector(OkHttpClient())
            val inspection = inspector.inspect(server.url("/initial").toString())

            assertEquals(200, inspection.httpStatus)
            assertEquals(1, inspection.redirectCount)
            assertEquals(2, inspection.redirectChain.size)
            assertTrue(inspection.finalUrl.endsWith("/asset.iso"))
            assertEquals(5242880L, inspection.contentLength)
            assertTrue(inspection.acceptRanges)
            assertEquals("\"iso-tag-123\"", inspection.etag)
            assertEquals("nginx/1.24", inspection.server)
            assertEquals("secret99", inspection.cookies["auth_token"])
            assertTrue(inspection.latencyMs >= 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resolverManagerChainsResolversByPriority() = runTest {
        val manager = ResolverManager(
            directUrlResolver = DirectUrlResolver(SourceDetector()),
            userscriptResolver = UserscriptResolver(object : UserscriptEngine {
                override suspend fun execute(metadata: UserscriptMetadata, targetUrl: String) =
                    UserscriptExecutionResult(success = false)
            }, UserscriptStorage(ApplicationProvider.getApplicationContext<Context>())),
            redirectResolver = RedirectResolver(OkHttpClient()),
            genericWebpageResolver = GenericWebpageResolver(OkHttpClient())
        )

        // Direct URL should match immediately
        val directRes = manager.resolve("https://example.com/test.zip")
        assertTrue(directRes is ResolveResult.Success)
        assertEquals("test.zip", (directRes as ResolveResult.Success).results.first().filename)
    }
}
