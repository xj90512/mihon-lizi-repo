package eu.kanade.tachiyomi.extension.zh.lizi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LiziProtocolTest {
    @Test fun signatureMatchesIndependentReference() {
        val path = "/app/api/category/list"
        val time = 1790953303L
        val reference = "87a957ba46f06f2476f917d60593bf4d" // Walter reference at the same fixed timestamp.
        val url = ("http://localhost" + LiziProtocol.signedPath("$path?page=3&random_seed=test%20x&lzsign=old&t=1", time)).toHttpUrl()
        assertEquals(reference, url.queryParameter("lzsign"))
        assertEquals(1, url.queryParameterValues("lzsign").size)
        assertEquals(1, url.queryParameterValues("t").size)
        assertEquals(time.toString(), url.queryParameter("t"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("test x", url.queryParameter("random_seed"))
    }

    @Test fun publicConfigurationParametersArePreserved() {
        val url = ("http://localhost" + LiziProtocol.signedPath("/app/api/configv2?platform=android", 1790953303)).toHttpUrl()
        assertEquals("android", url.queryParameter("platform"))
        assertEquals("com.jy.zyds", url.queryParameter("packname"))
        assertEquals("", url.queryParameter("appsign256"))
    }

    @Test fun tokenPrefixesAreNormalizedWithoutLogging() {
        assertEquals("test-token", LiziProtocol.normalizeToken("  Jwt: Bearer test-token  "))
        assertEquals("test-token", LiziProtocol.normalizeToken("bEaReR test-token"))
        assertEquals("", LiziProtocol.normalizeToken("Bearer  "))
    }

    @Test fun chapterOrderUsesOrderAndKeepsActualIds() {
        val input = Json.parseToJsonElement("""[{"id":900,"order":1},{"id":2,"order":3},{"id":800,"order":2}]""").jsonArray
        assertEquals(listOf("2", "800", "900"), LiziProtocol.chapters(input).map { it["id"]!!.jsonPrimitive.content })
    }

    @Test fun officialPageOrderIsNeverSortedByFilename() {
        val input = Json.parseToJsonElement("""["/Manual/book/1.webp","/Manual/book/49.webp","/Manual/book/2.webp"]""").jsonArray
        assertEquals(listOf("/Manual/book/1.webp", "/Manual/book/49.webp", "/Manual/book/2.webp"), LiziProtocol.pagePaths(input))
    }

    @Test fun imageMarkersAndHistoricalHostsUseSelectedCdn() {
        assertEquals("https://i.lzimg.xyz/Manual/book/2.webp", LiziProtocol.imageUrl("https://media.imgo.buzz/\$jp\$/Manual/book/2.webp", "https://i.lzimg.xyz"))
        assertEquals("/Manual/book/2.webp", LiziProtocol.imagePath("https://pic.lizimh.net/\$jp\$cf/Manual/book/2.webp"))
        assertEquals("https://media.imgo.buzz/Manual/book/2.webp", LiziProtocol.imageUrl("http://img2.mechat.fun/Manual/book/2.webp"))
        assertEquals("/Manual/book/2.webp", LiziProtocol.imagePath("https://t1.imge.store/Manual/book/2.webp"))
        assertEquals("https://other.invalid/image.webp", LiziProtocol.imageUrl("https://other.invalid/image.webp"))
    }

    @Test fun htmlAndBusinessFailuresDoNotBecomeEmptyLibraries() {
        assertThrows(IllegalStateException::class.java) { LiziProtocol.data("<html>blocked</html>", "/app/api/rank/list", 200) }
        assertThrows(IllegalStateException::class.java) { LiziProtocol.data("""{"code":400,"msg":"private data"}""", "/app/api/rank/list", 200) }
        assertThrows(IllegalStateException::class.java) { LiziProtocol.data("""{"code":201}""", "/app/api/rank/list", 200) }
        assertEquals("ok", LiziProtocol.data("""{"code":201,"data":{"value":"ok"}}""", "/app/api/rank/list", 200)["value"]!!.jsonPrimitive.content)
    }

    @Test fun invalidIdsAreRejectedBeforeAnyRequest() {
        assertEquals("123", LiziProtocol.id("https://mh.lizimh.net/comic/123"))
        assertThrows(IllegalArgumentException::class.java) { LiziProtocol.id("null") }
        assertThrows(IllegalArgumentException::class.java) { LiziProtocol.id("123?token=example") }
    }
}
