package eu.kanade.tachiyomi.extension.zh.lizi

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI

/** Bounded retries: public metadata and public image CDNs only, never account chapters. */
internal class LiziInterceptor(
    private val apiBase: String = LiziProtocol.API_BASE,
    private val publicFallback: String = LiziProtocol.PUBLIC_FALLBACK,
    private val imageBases: () -> List<String> = { LiziProtocol.imageBases },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val apiHost = URI(apiBase).host
        if (request.url.host == apiHost && request.url.encodedPath.startsWith("/app/api/")) {
            return api(chain, request)
        }
        val bases = imageBases()
        if (bases.any { URI(it).host == request.url.host }) return image(chain, request, bases)
        return chain.proceed(request)
    }

    private fun api(chain: Interceptor.Chain, request: Request): Response {
        // A chapter request can consume account quota. Do not retry it, or send a token over HTTP.
        if (request.url.encodedPath.startsWith("/app/api/chapter/") || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val response = try { chain.proceed(request) } catch (_: IOException) { null }
        if (response != null && response.code != 403 && response.code !in 500..599) return response
        response?.close()
        val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val retry = request.newBuilder()
            .url(publicFallback.trimEnd('/') + LiziProtocol.signedPath(path))
            .removeHeader("Authorization")
            .build()
        return try { chain.proceed(retry) } catch (_: IOException) {
            throw IOException("栗子接口 ${request.url.encodedPath} 连接失败，请检查网络后重试")
        }
    }

    private fun image(chain: Interceptor.Chain, request: Request, bases: List<String>): Response {
        val originalBase = request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        val ordered = (listOf(originalBase) + bases).map { it.trimEnd('/') }.distinct()
        val imagePath = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        for ((index, base) in ordered.withIndex()) {
            val attempt = request.newBuilder().url(base.trimEnd('/') + imagePath).removeHeader("Authorization").build()
            val response = try { chain.proceed(attempt) } catch (_: IOException) { null }
            if (response != null) {
                // Stop on rate limits. Image failover must not amplify a 429.
                if (response.code == 429 || response.code == 401) return response
                if (response.isSuccessful && isImage(response)) return response
                if (index == ordered.lastIndex && !response.isSuccessful) return response
                response.close()
            }
        }
        throw IOException("栗子图片线路暂时不可用，请稍后重试或在源设置切换图片线路")
    }

    private fun isImage(response: Response): Boolean {
        return LiziProtocol.isImage(response.peekBody(16).bytes())
    }
}
