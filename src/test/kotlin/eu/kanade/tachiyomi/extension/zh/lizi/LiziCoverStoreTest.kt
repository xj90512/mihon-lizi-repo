package eu.kanade.tachiyomi.extension.zh.lizi

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LiziCoverStoreTest {
    @Test fun coversSurviveANewSourceInstanceWithoutChangingCredentialsOrChapterIds() {
        val values = mutableMapOf("authorization" to "local-test-token")
        val preferences = memoryPreferences(values)
        LiziCoverStore(preferences).remember(chapters("""[{"id":42,"cover":"/book/chapter/4.jpg"}]"""))
        assertEquals("/book/chapter/4.jpg", LiziCoverStore(preferences).get("42"))
        assertEquals("local-test-token", values["authorization"])
        assertNull(LiziCoverStore(preferences).get("missing"))
        LiziCoverStore(preferences).remember(chapters("""[{"id":42,"cover":"/book/new/2.webp"}]"""))
        assertEquals("/book/new/2.webp", LiziCoverStore(preferences).get("42"))
    }

    @Test fun rememberedMetadataHasAFixedStorageBudget() {
        val preferences = memoryPreferences(mutableMapOf())
        val input = (1..4100).joinToString(",", "[", "]") { """{"id":$it,"cover":"/book/$it/1.jpg"}""" }
        LiziCoverStore(preferences).remember(chapters(input))
        val reloaded = LiziCoverStore(preferences)
        assertNull(reloaded.get("1"))
        assertEquals("/book/4100/1.jpg", reloaded.get("4100"))
    }

    private fun chapters(json: String) = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    private fun memoryPreferences(values: MutableMap<String, String>): SharedPreferences {
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
}
