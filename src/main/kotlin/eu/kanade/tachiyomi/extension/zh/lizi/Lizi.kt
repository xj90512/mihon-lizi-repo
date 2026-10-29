@file:Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")

package eu.kanade.tachiyomi.extension.zh.lizi

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 栗子漫画：Tachimanga / Mihon source. See README for protocol provenance and build instructions. */
class Lizi : HttpSource(), ConfigurableSource {

    override val name = "栗子漫画"

    override val baseUrl = LiziProtocol.API_BASE

    override val id: Long = LiziProtocol.SOURCE_ID

    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .retryOnConnectionFailure(false)
            .addInterceptor(LiziInterceptor(imageBases = { selectedImageBases() }))
            .build()
    }

    override val lang = "zh"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        liziPreferences(id)
    }

    // ------------------------------------------------------------------ 设置项

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_AUTHORIZATION
            title = "Authorization（登录令牌）"
            summary = "公开浏览和搜索不需要令牌；正文需要你自己的栗子账号授权。"
            dialogTitle = "Authorization"
            dialogMessage = "填写你自己的栗子账号 Authorization 值，支持裸 JWT、Jwt: 和 Bearer 前缀。账号的阅读时间、权限和额度仍由站方决定。"
            setDefaultValue("")
        }.also { screen.addPreference(it) }
        ListPreference(screen.context).apply {
            key = "image_base"
            title = "图片首选线路"
            entries = arrayOf("线路 1（官方 CDN）", "线路 2（官方图床）")
            entryValues = LiziProtocol.imageBases.toTypedArray()
            setDefaultValue(LiziProtocol.imageBases.first())
            summary = "优先使用所选线路，图片失败时有限次数切换；不会自动重试章节授权请求。"
        }.also { screen.addPreference(it) }
    }

    // ------------------------------------------------------------------ 请求头

    private val authorization: String
        get() = preferences.getString(PREF_AUTHORIZATION, "").orEmpty().trim()

    private fun authHeaderValue(): String? = LiziProtocol.normalizeToken(authorization).ifEmpty { null }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Dart/3.5 (dart:io)")
        .add("Accept", "application/json")

    private fun apiHeaders(chapter: Boolean = false): Headers = headersBuilder().apply {
        if (chapter) authHeaderValue()?.let { add("Authorization", it) }
    }.build()

    private fun apiRequest(path: String, chapter: Boolean = false): Request =
        GET(baseUrl + LiziProtocol.signedPath(path), apiHeaders(chapter))

    private fun selectedImageBases(): List<String> {
        val selected = preferences.getString("image_base", LiziProtocol.imageBases.first())
        return listOfNotNull(selected).filter { it in LiziProtocol.imageBases } + LiziProtocol.imageBases
    }

    /** 图片走 CDN，不需要也不应该带站点 token。 */
    private fun imageHeaders(): Headers = Headers.Builder().add("User-Agent", "Mozilla/5.0").build()

    // ------------------------------------------------------------------ 热门

    override fun popularMangaRequest(page: Int): Request =
        apiRequest("/app/api/rank/list")

    override fun popularMangaParse(response: Response): MangasPage {
        val data = dataOf(response)
        val ranks = data["rank_list"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = ranks
            .flatMap { group -> group.jsonObject.comicList() }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------ 最新

    override fun latestUpdatesRequest(page: Int): Request =
        apiRequest("/app/api/home/data")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = dataOf(response)
        val sections = data["home_content_list"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = sections
            .flatMap { section -> section.jsonObject.comicList() }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------ 搜索

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return apiRequest("/app/api/search/full?q=$q")
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = dataOf(response)
        val list = data["search_full"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = list.map { it.jsonObject.toSManga() }.distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------ 详情

    override fun mangaDetailsRequest(manga: SManga): Request =
        apiRequest("/app/api/v2/detail/${LiziProtocol.id(manga.url)}")

    override fun mangaDetailsParse(response: Response): SManga =
        dataOf(response).toSManga()

    // ------------------------------------------------------------------ 章节列表

    /** 章节表就在详情接口里，不需要第二个端点。 */
    override fun chapterListRequest(manga: SManga): Request =
        apiRequest("/app/api/v2/detail/${LiziProtocol.id(manga.url)}")

    override fun chapterListParse(response: Response): List<SChapter> {
        val detail = dataOf(response)
        val chapters = detail["chapters"]?.jsonArray ?: JsonArray(emptyList())
        return LiziProtocol.chapters(chapters).map { it.toSChapter() }
    }

    // ------------------------------------------------------------------ 正文

    override fun pageListRequest(chapter: SChapter): Request =
        apiRequest("/app/api/chapter/v3/${LiziProtocol.id(chapter.url)}", chapter = true)

    /**
     * 正文接口未登录时返回的是**真实 HTTP 401**，`asObservableSuccess()` 会在
     * `pageListParse` 之前就抛出 `HttpException(401)`，所以可操作的提示必须在这里给。
     */
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        if (authHeaderValue() == null) {
            return Observable.error(LoginRequiredException())
        }
        return super.fetchPageList(chapter).onErrorResumeNext { error: Throwable ->
            if (error.isUnauthorized()) {
                Observable.error(LoginRequiredException())
            } else {
                Observable.error(error)
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = dataOf(response)
        val pics = data["pics"]?.jsonArray ?: JsonArray(emptyList())
        return LiziProtocol.pagePaths(pics).mapIndexed { index, path ->
            Page(index, path, imageUrl(path))
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, imageHeaders())

    // ------------------------------------------------------------------ 解析工具

    private fun dataOf(response: Response): JsonObject = LiziProtocol.data(
        response.body?.string().orEmpty(), response.request.url.encodedPath, response.code,
    )

    private fun JsonObject.comicList(): List<SManga> =
        (this["comic_list"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject.toSManga() }

    private fun JsonObject.toSManga(): SManga = SManga.create().apply {
        val id = this@toSManga["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        url = id
        title = this@toSManga["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        thumbnail_url = imageUrlOrNull(this@toSManga["picY"]?.jsonPrimitive?.contentOrNull)
        author = this@toSManga["author"]?.jsonPrimitive?.contentOrNull
        artist = author
        description = buildDescription(this@toSManga)
        genre = this@toSManga["tags"]?.jsonPrimitive?.contentOrNull
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
        status = when (this@toSManga["isend"]?.jsonPrimitive?.intOrNull) {
            1 -> SManga.COMPLETED
            0 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        // 不在这里设 initialized：列表项标成已初始化会让 mihon 跳过详情请求，
        // 导致简介/标签缺失。交给宿主在 mangaDetailsParse 之后自己置位。
    }

    private fun buildDescription(obj: JsonObject): String {
        val sb = StringBuilder()
        obj["alias"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { sb.append("别名：").append(it.replace(",", " / ")).append('\n') }
        obj["author"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { sb.append("作者：").append(it).append('\n') }
        obj["nums"]?.jsonPrimitive?.intOrNull
            ?.let { sb.append("章节数：").append(it).append('\n') }
        obj["hits"]?.jsonPrimitive?.intOrNull
            ?.let { sb.append("点击：").append(it).append('\n') }
        obj["score"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?.let { sb.append("评分：").append(it).append('\n') }
        when (obj["isend"]?.jsonPrimitive?.intOrNull) {
            1 -> sb.append("状态：已完结\n")
            0 -> sb.append("状态：连载中\n")
        }
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(content)
        }
        return sb.toString().trim()
    }

    private fun JsonObject.toSChapter(): SChapter = SChapter.create().apply {
        url = this@toSChapter["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        name = this@toSChapter["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        chapter_number = this@toSChapter["order"]?.jsonPrimitive?.intOrNull?.toFloat() ?: -1f
        date_upload = parseIsoDate(this@toSChapter["created_at"]?.jsonPrimitive?.contentOrNull)
        scanlator = null
    }

    private fun Throwable.isUnauthorized(): Boolean =
        (this is HttpException && code == 401) || message?.contains("401") == true

    // ------------------------------------------------------------------ 图片 URL

    private fun imageUrlOrNull(path: String?): String? = path?.let { imageUrl(it) }

    private fun imageUrl(path: String): String =
        LiziProtocol.imageUrl(path, selectedImageBases().first())

    private fun parseIsoDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { ISO_FORMAT.get()!!.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    private companion object {
        const val PREF_AUTHORIZATION = "authorization"

        val ISO_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}

/**
 * 需要登录才能看正文。文案必须可操作——直接告诉用户去哪儿贴 token。
 */
class LoginRequiredException : IllegalStateException(
    "需要登录才能看正文：尚未填写有效授权，或栗子服务端拒绝了该授权。\n\n" +
        "请打开「浏览 → 栗子漫画 → 工具栏 ⋮ → 设置」，把登录令牌粘贴进 Authorization 再重试。\n" +
        "令牌取自你已登录的栗子漫画 App，支持裸 JWT、Jwt: 和 Bearer 前缀。",
)
