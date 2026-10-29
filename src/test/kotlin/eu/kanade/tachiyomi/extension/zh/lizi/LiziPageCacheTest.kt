package eu.kanade.tachiyomi.extension.zh.lizi

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlinx.serialization.json.*

class LiziPageCacheTest {
    private val bases = listOf("https://media.imgo.buzz", "https://t1.imge.store")
    private val urls = listOf("https://media.imgo.buzz/book/1.jpg", "https://t1.imge.store/book/2.webp", "https://media.imgo.buzz/book/10.jpg")

    @Test fun cacheSurvivesANewInstanceAndPreservesOrderAndCredentials() {
        val values = mutableMapOf("authorization" to "local-test-token")
        val prefs = memoryPagePreferences(values)
        LiziPageCache(prefs).put("/book/1.jpg", bases, urls)
        assertEquals(urls, LiziPageCache(prefs).get("/book/1.jpg", bases))
        assertEquals("local-test-token", values["authorization"])
    }

    @Test fun expiredEntriesAndChangedLineOrderDoNotHit() {
        var clock = 1000L
        val cache = LiziPageCache(now = { clock })
        cache.put("/book/1.jpg", bases, urls)
        assertEquals(urls, cache.get("/book/1.jpg", bases))
        assertNull(cache.get("/book/1.jpg", bases.reversed()))
        clock += 6 * 60 * 60 * 1000L + 1
        assertNull(cache.get("/book/1.jpg", bases))
    }

    @Test fun metadataRefreshInvalidatesOnlyRelatedChapters() {
        val prefs = memoryPagePreferences(mutableMapOf())
        val cache = LiziPageCache(prefs)
        cache.put("/book/1.jpg", bases, urls)
        cache.put("/other/1.jpg", bases, urls)
        cache.invalidate(listOf("/book/1.jpg"))
        val reloaded = LiziPageCache(prefs)
        assertNull(reloaded.get("/book/1.jpg", bases))
        assertEquals(urls, reloaded.get("/other/1.jpg", bases))
    }

    @Test fun entryBudgetEvictsOldResults() {
        val cache = LiziPageCache()
        for (i in 1..65) cache.put("/book/$i.jpg", bases, urls)
        assertNull(cache.get("/book/1.jpg", bases))
        assertEquals(urls, cache.get("/book/65.jpg", bases))
    }

    @Test fun aDamagedEmptyPageListIsACacheMiss() {
        val values = mutableMapOf<String, String>()
        val prefs = memoryPagePreferences(values)
        LiziPageCache(prefs).put("/book/1.jpg", bases, urls)
        assertEquals(urls, LiziPageCache(prefs).get("/book/1.jpg", bases))
        val key = values.keys.single()
        val root = Json.parseToJsonElement(values[key]!!).jsonObject
        values[key] = JsonObject(root.mapValues { (_, value) -> JsonObject(value.jsonObject + ("urls" to JsonArray(emptyList()))) }).toString()
        assertNull(LiziPageCache(prefs).get("/book/1.jpg", bases))
    }
}

internal fun memoryPagePreferences(values: MutableMap<String, String>): SharedPreferences {
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when (method.name) {
            "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
            "apply" -> null
            else -> throw UnsupportedOperationException(method.name)
        }
    }
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString" -> values[args!![0] as String] ?: args[1]
            "edit" -> editor
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SharedPreferences
}
