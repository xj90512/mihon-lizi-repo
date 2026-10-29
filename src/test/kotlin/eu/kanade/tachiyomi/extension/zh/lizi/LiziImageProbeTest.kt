package eu.kanade.tachiyomi.extension.zh.lizi

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class LiziImageProbeTest {
    @Test fun findsLeadingGapAndKeepsNumericOrderWithoutAuthorization() {
        MockWebServer().use { server ->
            val requests = ConcurrentLinkedQueue<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val n = request.path!!.substringAfterLast('/').substringBefore('.').toInt()
                    return if (n in 5..13) image() else MockResponse().setResponseCode(404)
                }
            }
            val paths = probe(server).pages("/Manual/book/chapter/8.jpg")
            assertEquals((5..13).map { "/Manual/book/chapter/$it.jpg" }, paths)
            assertTrue(requests.isNotEmpty())
            requests.forEach { assertNull(it.getHeader("Authorization")); assertEquals("bytes=0-15", it.getHeader("Range")) }
            assertTrue(server.requestCount <= 18)
        }
    }

    @Test fun tinyPlaceholdersDoNotExtendTheChapter() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.endsWith("/1.jpg")) image() else image(82)
            }
            assertEquals(listOf("/book/1.jpg"), probe(server).pages("/book/1.jpg"))
        }
    }

    @Test fun serverErrorsAreNotMistakenForChapterEnd() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
            val error = assertThrows(IOException::class.java) { probe(server).pages("/book/1.jpg") }
            assertTrue(error.message!!.contains("未将网络错误当作章节结尾"))
        }
    }

    @Test fun htmlIsNotAChapterImage() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>blocked</html>")
            }
            assertThrows(IOException::class.java) { probe(server).pages("/book/1.jpg") }
        }
    }

    @Test fun deniedOrLimitedImagesNeverSwitchToAnotherHost() {
        for (status in listOf(401, 403, 429)) {
            MockWebServer().use { server -> MockWebServer().use { backup ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(status)
                }
                val resolver = LiziImageProbe(OkHttpClient(), { listOf(server.url("/").toString(), backup.url("/").toString()) }, maxPages = 16)
                assertThrows(IOException::class.java) { resolver.pages("/book/1.jpg") }
                assertEquals(0, backup.requestCount)
                assertTrue(server.requestCount <= 4)
            } }
        }
    }

    @Test fun hardLimitFailsInsteadOfPublishingATruncatedList() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = image()
            }
            val error = assertThrows(IOException::class.java) { probe(server).pages("/book/1.jpg") }
            assertTrue(error.message!!.contains("探测上限"))
            assertEquals(16, server.requestCount)
        }
    }

    @Test fun unrelatedAbsoluteHostsAndNonnumericFilenamesAreRejectedBeforeNetworking() {
        MockWebServer().use { server ->
            assertThrows(IOException::class.java) { probe(server).pages("https://other.invalid/book/1.jpg") }
            assertThrows(IOException::class.java) { probe(server).pages("/book/cover.jpg") }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun trailingMissesDoNotHideAnErrorInTheSameBatch() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = request.path!!.substringAfterLast('/').substringBefore('.').toInt()
                    return when (n) { 11, 12 -> MockResponse().setResponseCode(404); 13 -> MockResponse().setResponseCode(503); else -> image() }
                }
            }
            assertThrows(IOException::class.java) { probe(server).pages("/book/1.jpg") }
        }
    }

    @Test fun overallDeadlineCancelsOutstandingImageRequests() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = image().setHeadersDelay(500, TimeUnit.MILLISECONDS)
            }
            val resolver = LiziImageProbe(OkHttpClient(), { listOf(server.url("/").toString()) }, maxPages = 16, timeoutMillis = 100)
            val error = assertThrows(IOException::class.java) { resolver.pages("/book/1.jpg") }
            assertTrue(error.message!!.contains("超时"))
        }
    }

    @Test fun aVerifiedCoverPastEarlyGapsPreventsPrematureTruncation() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = request.path!!.substringAfterLast('/').substringBefore('.').toInt()
                    return if (n in listOf(1, 2, 25)) image() else MockResponse().setResponseCode(404)
                }
            }
            val resolver = LiziImageProbe(OkHttpClient(), { listOf(server.url("/").toString()) }, maxPages = 32)
            assertEquals(listOf("/book/1.jpg", "/book/2.jpg", "/book/25.jpg"), resolver.pages("/book/25.jpg"))
        }
    }

    private fun probe(server: MockWebServer) = LiziImageProbe(OkHttpClient(), { listOf(server.url("/").toString()) }, maxPages = 16)

    private fun image(total: Int = 4096) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes 0-15/$total").setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(13)))
}
