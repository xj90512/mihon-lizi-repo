package eu.kanade.tachiyomi.extension.zh.lizi

import android.content.SharedPreferences
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Bounded cache of fully checked public URLs, scoped by cover and selected line order. */
internal class LiziPageCache(
    private val preferences: SharedPreferences? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var generation = 0L
    private data class Entry(val cover: String, val at: Long, val urls: List<String>)
    private val entries: LinkedHashMap<String, Entry> by lazy {
        runCatching {
            val root = Json.parseToJsonElement(preferences?.getString(KEY, "{}") ?: "{}").jsonObject
            LinkedHashMap(root.mapValues { (_, value) ->
                val obj = value.jsonObject
                Entry(obj["cover"]!!.jsonPrimitive.content, obj["at"]!!.jsonPrimitive.long,
                    obj["urls"]!!.jsonArray.map { it.jsonPrimitive.content })
            })
        }.getOrDefault(linkedMapOf())
    }

    @Synchronized fun get(cover: String, bases: List<String>): List<String>? {
        val key = key(cover, bases)
        val entry = entries[key] ?: return null
        if (entry.urls.isEmpty() || entry.urls.size > 600 || now() - entry.at !in 0 until TTL) {
            entries.remove(key)
            return null
        }
        return entry.urls.toList()
    }

    @Synchronized fun generation(): Long = generation

    @Synchronized fun put(cover: String, bases: List<String>, urls: List<String>, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != generation) return
        if (urls.isEmpty() || urls.size > 600) return
        val key = key(cover, bases)
        entries.remove(key)
        entries[key] = Entry(cover, now(), urls.toList())
        while (entries.size > 64) entries.remove(entries.keys.first())
        persist()
    }

    @Synchronized fun invalidate(cover: String) {
        invalidate(listOf(cover))
    }

    @Synchronized fun invalidate(covers: Collection<String>) {
        if (covers.isEmpty()) return
        // A refresh also blocks pending writes, even when no old entry exists yet.
        generation++
        val set = covers.toHashSet()
        if (entries.entries.removeAll { it.value.cover in set }) persist()
    }

    private fun persist() {
        fun encode() = JsonObject(entries.mapValues { (_, e) -> JsonObject(mapOf(
            "cover" to JsonPrimitive(e.cover), "at" to JsonPrimitive(e.at),
            "urls" to JsonArray(e.urls.map { JsonPrimitive(it) }),
        )) }).toString()
        var encoded = encode()
        while (encoded.length > 512_000 && entries.isNotEmpty()) {
            entries.remove(entries.keys.first())
            encoded = encode()
        }
        preferences?.edit()?.putString(KEY, encoded)?.apply()
    }

    private fun key(cover: String, bases: List<String>) = MessageDigest.getInstance("SHA-256")
        .digest((cover + "\n" + bases.joinToString("\n")).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object { const val KEY = "verified_public_pages_v1"; const val TTL = 6 * 60 * 60 * 1000L }
}
