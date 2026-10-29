package eu.kanade.tachiyomi.extension.zh.lizi

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Public cover paths only. Chapter URLs/IDs remain unchanged for existing libraries. */
internal class LiziCoverStore(private val preferences: SharedPreferences) {
    @Synchronized fun get(chapterId: String): String? = read()[chapterId]

    @Synchronized fun remember(chapters: List<JsonObject>) {
        val values = read()
        for (chapter in chapters) {
            val id = chapter["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val cover = chapter["cover"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            if (!id.matches(Regex("[0-9]+"))) continue
            values.remove(id)
            values[id] = cover
        }
        while (values.size > 4096) values.remove(values.keys.first())
        preferences.edit().putString(KEY, JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()).apply()
    }

    private fun read(): LinkedHashMap<String, String> = runCatching {
        val json = Json.parseToJsonElement(preferences.getString(KEY, "{}").orEmpty()) as JsonObject
        LinkedHashMap(json.mapValues { it.value.jsonPrimitive.content })
    }.getOrDefault(linkedMapOf())

    private companion object { const val KEY = "public_chapter_covers_v1" }
}
