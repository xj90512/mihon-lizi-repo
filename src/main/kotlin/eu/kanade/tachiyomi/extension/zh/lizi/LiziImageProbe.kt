package eu.kanade.tachiyomi.extension.zh.lizi

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Discover numbered public images within one metadata-provided directory, with a fixed budget. */
internal class LiziImageProbe(
    client: OkHttpClient,
    private val imageBases: () -> List<String>,
    private val maxPages: Int = 600,
    private val timeoutMillis: Long = 60_000,
) {
    private val client = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build()

    fun pages(cover: String): List<String> {
        val match = Regex("^(/[^?#]+)/(\\d+)\\.(jpg|jpeg|png|webp|avif)$", RegexOption.IGNORE_CASE)
            .matchEntire(LiziProtocol.imagePath(cover))
            ?: throw IOException("本章图片路径不支持探测，请填写 Authorization 使用官方图片列表")
        val directory = match.groupValues[1]
        val coverPage = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..maxPages }
        val extension = match.groupValues[3]
        val bases = imageBases().map { it.trimEnd('/') }.distinct()
        require(bases.isNotEmpty()) { "未配置图片线路" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val stopped = AtomicBoolean(false)
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val pool = Executors.newFixedThreadPool(4)
        val existing = ArrayList<String>()
        var consecutiveMissing = 0
        var coverExists: Boolean? = null
        try {
            // Check the first ten pages together: some uploads begin at page 5 instead of 1.
            var start = 1
            var batchSize = minOf(10, maxPages)
            while (start <= maxPages) {
                val numbers = (start until minOf(start + batchSize, maxPages + 1)).toList()
                // Verify the supplied cover as an anchor, but do not trust stale/missing covers.
                val requested = if (start == 1 && coverPage != null && coverPage !in numbers) numbers + coverPage else numbers
                val tasks = requested.map { number -> Callable {
                    val path = "$directory/$number.$extension"
                    (if (number == coverPage && coverExists != null) coverExists!! else exists(path, bases, stopped, calls)) to path
                } }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("本章图片探测超时，请稍后重试")
                val futures = pool.invokeAll(tasks, remaining, TimeUnit.NANOSECONDS)
                // Consume every result before deciding where the chapter ends. A later task may
                // have found another page, or failed with a network error/429 after two misses.
                val results = futures.map { future ->
                    if (future.isCancelled) throw IOException("本章图片探测超时，请稍后重试")
                    try { future.get() } catch (e: ExecutionException) {
                        throw (e.cause as? IOException ?: IOException("本章图片探测失败", e.cause))
                    }
                }
                if (start == 1 && coverPage != null) coverExists = results[requested.indexOf(coverPage)].first
                for ((found, path) in results.take(numbers.size)) {
                    if (found) {
                        existing.add(path)
                        consecutiveMissing = 0
                    } else if (existing.isNotEmpty()) {
                        consecutiveMissing++
                    }
                }
                if (start == 1 && existing.isEmpty() && coverExists != true) {
                    throw IOException("本章未探测到可用图片，请切换线路或填写 Authorization")
                }
                val verifiedAnchor = if (coverExists == true) coverPage!! else 0
                if (consecutiveMissing >= 2 && numbers.last() >= verifiedAnchor) return existing
                start += numbers.size
                batchSize = 4
            }
            throw IOException("本章达到 $maxPages 页探测上限，请填写 Authorization 获取完整官方列表")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("本章图片探测已取消", e)
        } finally {
            stopped.set(true)
            calls.forEach { it.cancel() }
            pool.shutdownNow()
        }
    }

    private fun exists(path: String, bases: List<String>, stopped: AtomicBoolean, calls: MutableSet<Call>): Boolean {
        var uncertain = false
        for (base in bases) {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw IOException("本章图片探测已停止")
            val request = Request.Builder().url(base + path)
                .header("User-Agent", "Mozilla/5.0").header("Range", "bytes=0-15").build()
            val call = client.newCall(request)
            calls.add(call)
            try {
                call.execute().use { response ->
                    when (response.code) {
                        401, 403, 429 -> {
                            stopped.set(true)
                            throw IOException("图片线路返回 HTTP ${response.code}，已停止探测，请稍后重试或检查账号权限")
                        }
                        404, 410 -> Unit
                        200, 206 -> {
                            val bytes = response.peekBody(1024).bytes()
                            val total = if (response.code == 206) {
                                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            } else response.body?.contentLength()?.takeIf { it >= 0 } ?: bytes.size.toLong()
                            if (LiziProtocol.isImage(bytes) && total != null) {
                                // The site also serves an 82-byte placeholder as HTTP 200.
                                if (total >= 1024) return true
                            } else uncertain = true
                        }
                        else -> uncertain = true
                    }
                }
            } catch (e: IOException) {
                if (stopped.get()) throw e
                uncertain = true
            } finally { calls.remove(call) }
        }
        if (uncertain) throw IOException("图片线路连接或响应异常，未将网络错误当作章节结尾；请稍后重试")
        return false
    }
}
