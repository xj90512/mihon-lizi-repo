package eu.kanade.tachiyomi.extension.zh.lizi

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.IOException

class LiziInterceptorTest {
    @Test fun metadataFallbackIsSignedAndHasNoAuthorization() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setResponseCode(503))
            backup.enqueue(MockResponse().setBody("""{"code":201,"data":{}}"""))
            val client = client(primary, backup)
            client.newCall(Request.Builder().url(primary.url("/app/api/search/full?q=a%20b")).build()).execute().use {
                assertEquals(200, it.code)
            }
            val request = backup.takeRequest(1, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization"))
            assertEquals("a b", request.requestUrl!!.queryParameter("q"))
            assertEquals(32, request.requestUrl!!.queryParameter("lzsign")!!.length)
        } }
    }

    @Test fun accountChapterFailuresAreNeverRetried() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setResponseCode(503))
            client(primary, backup).newCall(Request.Builder().url(primary.url("/app/api/chapter/v3/123"))
                .header("Authorization", "test-token").build()).execute().use { assertEquals(503, it.code) }
            assertEquals(1, primary.requestCount)
            assertEquals(0, backup.requestCount)
        } }
    }

    @Test fun rateLimitedMetadataIsNotSentToAnotherHost() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setResponseCode(429))
            client(primary, backup).newCall(Request.Builder().url(primary.url("/app/api/rank/list")).build())
                .execute().use { assertEquals(429, it.code) }
            assertEquals(0, backup.requestCount)
        } }
    }

    @Test fun accountChapterDisconnectIsNeverRetried() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            primary.enqueue(MockResponse().setBody("unexpected retry"))
            try {
                client(primary, backup).newCall(Request.Builder().url(primary.url("/app/api/chapter/v3/123"))
                    .header("Authorization", "test-token").build()).execute().close()
                fail("Expected connection failure")
            } catch (_: IOException) { }
            assertEquals(1, primary.requestCount)
            assertEquals(0, backup.requestCount)
        } }
    }

    @Test fun allImageHostsReturningHtmlFailClearly() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setBody("<html>blocked</html>"))
            backup.enqueue(MockResponse().setBody("<html>blocked too</html>"))
            val bases = listOf(primary.url("/").toString(), backup.url("/").toString())
            val client = OkHttpClient.Builder().addInterceptor(LiziInterceptor(
                apiBase = "https://api.invalid", imageBases = { bases },
            )).build()
            try {
                client.newCall(Request.Builder().url(primary.url("/Manual/cover.jpg")).build()).execute().close()
                fail("HTML must not be returned as a successful image")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("图片线路暂时不可用"))
            }
            assertEquals(1, primary.requestCount)
            assertEquals(1, backup.requestCount)
        } }
    }

    @Test fun imageFallbackRejectsHtmlAndRemovesCredentials() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>blocked</html>"))
            backup.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00))))
            val bases = listOf(primary.url("/").toString(), backup.url("/").toString())
            val client = OkHttpClient.Builder().addInterceptor(LiziInterceptor(
                apiBase = "https://api.invalid", imageBases = { bases },
            )).build()
            client.newCall(Request.Builder().url(primary.url("/Manual/book/49.webp"))
                .header("Authorization", "test-token").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals(4, it.body!!.bytes().size)
            }
            assertNull(primary.takeRequest().getHeader("Authorization"))
            val retry = backup.takeRequest()
            assertNull(retry.getHeader("Authorization"))
            assertEquals("/Manual/book/49.webp", retry.path)
        } }
    }

    private fun client(primary: MockWebServer, backup: MockWebServer) = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(LiziInterceptor(primary.url("/").toString(), backup.url("/").toString()))
        .callTimeout(5, TimeUnit.SECONDS).build()
}
