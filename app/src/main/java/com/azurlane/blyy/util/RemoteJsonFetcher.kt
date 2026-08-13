package com.azurlane.blyy.util

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程配置内容的编码方式。
 *
 * - [PLAIN]：响应体即为配置文件原文（如 jsDelivr CDN）
 * - [BASE64]：官方 Contents API 返回的 JSON 中 `content` 字段为 base64 编码（如 Gitee / GitHub）
 */
enum class RemoteJsonEncoding {
    PLAIN,
    BASE64
}

/**
 * 一个远程配置源。
 *
 * @param url      源地址
 * @param encoding 响应编码方式
 * @param referer  随请求携带的 Referer（模拟真实来源页面，降低被风控识别为自动化的概率）；留空则不携带
 */
data class RemoteJsonSource(
    val url: String,
    val encoding: RemoteJsonEncoding = RemoteJsonEncoding.PLAIN,
    val referer: String = ""
)

/**
 * 远程 JSON 配置文件获取器（多源串行降级 + 反滥用安全策略）。
 *
 * 供 [NetworkDriveLinkProvider] / [SdResourceLinkProvider] 复用，从代码仓库在线获取
 * 小型 JSON 配置文件（如 `network_drive_links.json`），并统一实现安全、合规的获取方式。
 *
 * ## 为什么不用 raw 直链
 *
 * Gitee / GitHub 的 raw 直链（`/raw/...`、`raw.githubusercontent.com`）会被托管平台
 * 视为"资源滥用"的高危行为：高频访问、携带缓存破坏参数（`?_t=ts`）、非浏览器 UA
 * 等特征都会触发风控（Access denied / 验证页）。因此本类**优先使用官方 Contents API**
 * （合规、不缓存、内容最新），仅将 CDN（jsDelivr）作为最后兜底。
 *
 * ## 反滥用安全策略
 *
 * 1. **串行降级而非并发竞速**：任意时刻最多 1 个在途请求，避免瞬时多请求特征
 * 2. **移除缓存破坏参数**：高频变化的 query 参数是典型自动化特征，且官方 API 本身不缓存
 * 3. **浏览器化请求头**：User-Agent / Accept / Accept-Language / Referer，模拟真实访问
 * 4. **失败退避重试 + 随机抖动**：避免快速连打触发风控
 * 5. **最小请求间隔**：同一实例连续请求至少间隔一段时间
 * 6. **响应内容校验**：仅接受以 `{` 开头的 JSON 对象，验证页 / 错误页直接丢弃
 *
 * ## 及时性
 *
 * 官方 Contents API 不缓存、每次回源，因此只要获取成功即拿到仓库最新内容；
 * 配合各 Provider 的内存 / 持久化二级缓存，兼顾"及时更新"与"离线兜底"。
 */
@Singleton
class RemoteJsonFetcher @Inject constructor() {

    /**
     * 按顺序尝试各源，返回第一个成功获取并解码、且校验通过（JSON 对象）的配置原文；
     * 全部源均失败时返回 null。
     *
     * @param sources              按优先级排列的源列表（主源在前，串行降级）
     * @param maxAttemptsPerSource 单个源失败后的重试次数上限（含首次），指数退避 + 抖动
     * @return 配置文件原文（JSON 对象文本）；全部失败返回 null
     */
    suspend fun fetchWithFallback(
        sources: List<RemoteJsonSource>,
        maxAttemptsPerSource: Int = MAX_ATTEMPTS_PER_SOURCE
    ): String? = withContext(Dispatchers.IO) {
        for (source in sources) {
            throttleIfNeeded()
            var success: String? = null
            repeat(maxAttemptsPerSource) { attempt ->
                if (attempt > 0) {
                    val backoff = RETRY_BASE_DELAY_MS * (1L shl attempt) + randomJitter()
                    Log.w(TAG, "Retry $attempt/$maxAttemptsPerSource for ${source.url} in ${backoff}ms")
                    delay(backoff)
                }
                success = fetchSource(source)
                if (success != null) return@repeat
            }
            if (success != null) {
                Log.i(TAG, "Source OK: ${source.url}")
                return@withContext success
            }
            Log.w(TAG, "Source exhausted: ${source.url}")
        }
        null
    }

    /**
     * 从单个源获取并解码配置内容。
     */
    private fun fetchSource(source: RemoteJsonSource): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(source.url)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            // 浏览器 UA + Accept-Language：显著降低被托管平台风控拦截（验证页/CAPTCHA）的概率
            connection.setRequestProperty("User-Agent", BROWSER_UA)
            connection.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE)
            if (source.referer.isNotBlank()) {
                connection.setRequestProperty("Referer", source.referer)
            }
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code from ${source.url}")
                return null
            }

            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            when (source.encoding) {
                RemoteJsonEncoding.PLAIN -> decodeValidateJson(raw)
                RemoteJsonEncoding.BASE64 -> decodeBase64Json(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed (${source.url}): ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析官方 Contents API 响应，解出 base64 编码的文件内容并校验。
     *
     * 响应示例：
     * ```
     * { "type": "file", "encoding": "base64", "content": "eyJwYW5fdXJsIjog..." }
     * ```
     * 若响应缺 `content` 字段（如限流/鉴权错误页），视为失败返回 null。
     */
    private fun decodeBase64Json(response: String): String? {
        return try {
            val root = JSONObject(response)
            val content = root.optString("content")
            if (content.isBlank()) {
                Log.w(TAG, "Contents API response has no content field (rate limit / error page?)")
                return null
            }
            val decoded = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
            decodeValidateJson(decoded)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode base64 content: ${e.message}")
            null
        }
    }

    /**
     * 校验文本是否为 JSON 对象（以 `{` 开头）。
     *
     * Gitee / GitHub 可能对异常请求返回 HTML 验证页或错误提示（HTTP 200 但内容非 JSON），
     * 通过此校验明确丢弃这类响应，避免把验证页当作配置解析。
     */
    private fun decodeValidateJson(text: String): String? {
        if (!text.trimStart().startsWith("{")) {
            Log.w(TAG, "Non-JSON response, likely verification/error page (head: ${text.trim().take(80)})")
            return null
        }
        return text
    }

    /** 最小请求间隔控制：连续请求间隔低于阈值时等待，避免瞬时高频请求触发风控 */
    private suspend fun throttleIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastFetchAt
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - elapsed)
        }
        lastFetchAt = System.currentTimeMillis()
    }

    /** 随机抖动（毫秒）：退避时加入随机分量，进一步弱化自动化特征 */
    private fun randomJitter(): Long = (Math.random() * JITTER_MAX_MS).toLong()

    @Volatile
    private var lastFetchAt: Long = 0L

    companion object {
        private const val TAG = "RemoteJsonFetcher"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"
        private const val MAX_ATTEMPTS_PER_SOURCE = 2
        private const val RETRY_BASE_DELAY_MS = 500L
        private const val JITTER_MAX_MS = 300L
        /** 同一实例连续请求最小间隔（毫秒）：防止瞬时高频请求被判定为滥用 */
        private const val MIN_REQUEST_INTERVAL_MS = 800L
    }
}
