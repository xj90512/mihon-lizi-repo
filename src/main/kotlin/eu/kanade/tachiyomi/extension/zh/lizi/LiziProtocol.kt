package eu.kanade.tachiyomi.extension.zh.lizi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.MessageDigest

/** Protocol behavior independently checked against Walter498/Walter (d178a49). */
internal object LiziProtocol {
    const val API_BASE = "https://mh.lizimh.net"
    const val PUBLIC_FALLBACK = "http://ai.xajtl.com"
    const val SOURCE_ID = 6794114338793425275L
    // Current public configv2: plain CDN for ordinary paths, R2 for $jp$ paths.
    // Walter's historical lzimg/imgio defaults no longer resolve (2026-10-29).
    val imageBases = listOf("https://media.imgo.buzz", "https://t1.imge.store")

    fun imageVariants(path: String): List<String> {
        val match = Regex("^(.*?/\\d+)\\.(jpg|jpeg|png|webp|avif)$", RegexOption.IGNORE_CASE).matchEntire(path)
            ?: return listOf(path)
        return (listOf(path) + listOf("webp", "jpg", "png", "jpeg", "avif").map { "${match.groupValues[1]}.$it" }).distinct()
    }

    fun isImage(bytes: ByteArray): Boolean = bytes.size >= 3 && (
        (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) ||
            (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes.copyOfRange(1, 4).contentEquals("PNG".toByteArray())) ||
            (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") ||
            (bytes.size >= 12 && String(bytes, 4, 8, Charsets.US_ASCII).startsWith("ftypavif"))
        )

    // Public application protocol constant, not an account credential.
    private const val SIGN_PREFIX = "q2sIObYXCp2uBZgCNBlY93J3z67hK0wS"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val historicalImageHosts = setOf(
        "pic.lizimh.net", "img2.mechat.fun", "img.mechat.fun", "media.imgo.buzz",
        "cdn.lzimg.xyz", "i.lzimg.xyz", "cf-1.imgio.club", "t1.imge.store",
    )

    fun signedPath(target: String, seconds: Long = System.currentTimeMillis() / 1000): String {
        require(seconds.toString().matches(Regex("[0-9]{10}"))) { "栗子请求时间无效，请检查设备时间" }
        val path = target.substringBefore('?')
        require(path.startsWith("/app/api/")) { "无效的栗子接口路径" }
        val query = target.substringAfter('?', "").split('&').filter {
            it.isNotEmpty() && it.substringBefore('=') !in setOf("lzsign", "t")
        }.toMutableList()
        fun default(key: String, value: String) {
            if (query.none { it.substringBefore('=') == key }) query.add("$key=$value")
        }
        if (path == "/app/api/home/data" || path == "/app/api/configv2") {
            default("packname", "com.jy.zyds")
            default("appsign256", "")
        }
        if (path == "/app/api/configv2") default("platform", "ios")
        val digest = MessageDigest.getInstance("MD5").digest("$SIGN_PREFIX$path$seconds".toByteArray(Charsets.UTF_8))
        val signature = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        query.add("lzsign=$signature")
        query.add("t=$seconds")
        return "$path?${query.joinToString("&")}"
    }

    fun normalizeToken(value: String): String =
        value.trim().replace(Regex("^(?:(?:jwt:|bearer)\\s*)+", RegexOption.IGNORE_CASE), "").trim()

    fun id(value: String): String {
        val candidate = value.trim().trimEnd('/').substringAfterLast('/')
        require(candidate.matches(Regex("[0-9]+"))) { "栗子漫画或章节 ID 无效，请刷新详情" }
        return candidate
    }

    fun data(body: String, path: String, httpCode: Int): JsonObject {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw IllegalStateException("栗子接口 $path 返回非 JSON 内容（HTTP $httpCode）")
        }
        when (val code = root["code"]?.jsonPrimitive?.intOrNull) {
            200, 201 -> Unit
            401 -> throw LoginRequiredException()
            429 -> throw IllegalStateException("栗子接口请求过于频繁，请稍后重试")
            else -> throw IllegalStateException("栗子接口 $path 返回业务错误（code=$code）")
        }
        return root["data"] as? JsonObject
            ?: throw IllegalStateException("栗子接口 $path 缺少有效 data 对象")
    }

    /** Preserve official chapter numbers and IDs; never infer order from an ID. */
    fun chapters(items: JsonArray): List<JsonObject> = items.map { it.jsonObject }
        .filter { !it["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
        .sortedByDescending { it["order"]?.jsonPrimitive?.intOrNull ?: 0 }

    /** The server's array is authoritative, including non-numeric filenames. */
    fun pagePaths(items: JsonArray): List<String> = items.map {
        it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("栗子章节包含空图片地址，请刷新章节")
    }.also { require(it.isNotEmpty()) { "栗子章节没有返回图片，请检查登录状态或账号额度" } }

    fun imagePath(value: String): String {
        val marker = value.indexOf("\$jp\$")
        if (marker >= 0) {
            var path = value.substring(marker + 4)
            if (path.startsWith("cf/")) path = path.substring(2)
            return "/" + path.trimStart('/')
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri?.host?.lowercase() in historicalImageHosts) {
                return uri!!.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            }
        }
        return value
    }

    fun imageUrl(value: String, base: String = imageBases.first()): String {
        if (value.isBlank()) return ""
        val path = imagePath(value)
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        require(!path.startsWith("//")) { "栗子图片地址无效" }
        return base.trimEnd('/') + "/" + path.trimStart('/')
    }
}
