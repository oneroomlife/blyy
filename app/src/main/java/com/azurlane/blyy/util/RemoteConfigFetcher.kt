package com.azurlane.blyy.util

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一远程配置获取层。
 *
 * 从代码仓库的 `network_drive_links.json` 文件获取完整 JSON 配置，
 * 供 [NetworkDriveLinkProvider] 和 [SdResourceLinkProvider] 共享使用。
 *
 * ## 核心优化（避免被仓库判定为"滥用 raw 链接"）
 *
 * 1. **主源改用 Gitee Open API**（非 raw 链接）：
 *    - 官方编程接口，不会触发 raw 链接的反爬/验证机制
 *    - 支持 ETag 条件请求（304 Not Modified 零带宽消耗）
 *    - 公开仓库无需认证即可访问
 *
 * 2. **Gitee raw 降级为末位兜底**：
 *    - 仅在 Gitee API、GitHub raw、jsDelivr 全部失败时才使用
 *    - 最大限度减少对 Gitee raw 链接的请求频次
 *
 * 3. **请求去重**：
 *    - 多个 Provider 并发调用时共享同一次 HTTP 请求（Mutex + double-check）
 *    - 两个 Provider 合计请求量减半
 *
 * 4. **ETag 条件请求**：
 *    - 首次请求后缓存 ETag，后续请求携带 `If-None-Match`
 *    - 服务端返回 304 时直接使用内存缓存，零带宽消耗
 *    - 替代了原先的 `?_t=timestamp` cache-busting（后者反而暴露自动化特征）
 *
 * 5. **浏览器请求头**：
 *    - 携带 `Referer`、`Accept-Language` 等头，使请求更接近正常浏览器访问
 *
 * ## 源策略
 *
 * | 优先级 | 源            | 特点                                              | ETag | CDN 缓存 |
 * |--------|---------------|---------------------------------------------------|------|----------|
 * | 1      | Gitee API     | 官方编程接口，不触发 raw 反爬，支持 ETag            | 是   | 无       |
 * | 2      | GitHub raw    | 官方直链，内容最新，国内访问慢                      | 否   | 无       |
 * | 3      | jsDelivr CDN  | 国内 CDN 加速，有数小时缓存                         | 否   | 有       |
 * | 4      | Gitee raw     | 末位兜底，仅在以上全部失败时使用（最小化 raw 请求）  | 否   | 无       |
 *
 * ## 缓存策略
 * - 内存缓存（60 秒 TTL）：避免短时间内重复请求，两个 Provider 共享
 * - 请求去重（Mutex）：并发调用共享同一 HTTP 请求
 * - ETag 条件请求：减少带宽消耗，304 响应不消耗流量
 *
 * @param forceRefresh true 时跳过内存缓存，使用主源优先策略确保拿到最新内容
 */
@Singleton
class RemoteConfigFetcher @Inject constructor() {

    companion object {
        private const val TAG = "RemoteConfigFetcher"

        /** 源 1（主源）：Gitee Open API（官方编程接口，非 raw 链接，支持 ETag 条件请求） */
        private const val SOURCE_GITEE_API =
            "https://gitee.com/api/v5/repos/dreamweavers-whisper/blyy/contents/network_drive_links.json?ref=master"

        /** 源 2：GitHub raw（官方直链，内容最新，国内访问慢 4-5s） */
        private const val SOURCE_GITHUB_RAW =
            "https://raw.githubusercontent.com/oneroomlife/blyy/main/network_drive_links.json"

        /** 源 3：jsDelivr CDN（国内 CDN 加速，有数小时缓存） */
        private const val SOURCE_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/oneroomlife/blyy@latest/network_drive_links.json"

        /** 源 4（末位兜底）：Gitee raw（仅在以上全部失败时使用，最小化 raw 请求频次） */
        private const val SOURCE_GITEE_RAW =
            "https://gitee.com/dreamweavers-whisper/blyy/raw/master/network_drive_links.json"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 6_000

        /** 内存缓存有效期（毫秒）。60 秒：配置文件变更频率低，平衡新鲜度与请求频率 */
        private const val CACHE_TTL_MS = 60_000L

        /** 主源（Gitee API）优先等待窗口（毫秒） */
        private const val PRIMARY_TIMEOUT_MS = 5_000L

        /** 启动竞速模式总超时（毫秒） */
        private const val RACE_TIMEOUT_MS = 6_000L

        /** 浏览器 UA（Gitee 对非浏览器 UA 可能返回验证页） */
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Gitee Referer（使请求看起来来自仓库页面浏览，而非自动化脚本） */
        private const val GITEE_REFERER = "https://gitee.com/dreamweavers-whisper/blyy"
    }

    /** 缓存的完整 JSON 配置 */
    @Volatile
    private var cachedJson: JSONObject? = null

    /** 缓存时间戳（毫秒） */
    @Volatile
    private var cachedAt: Long = 0L

    /** Gitee API 返回的 ETag，用于条件请求（304 Not Modified） */
    @Volatile
    private var cachedEtag: String? = null

    /** 请求去重锁：并发调用共享同一 HTTP 请求 */
    private val mutex = Mutex()

    /**
     * 获取远程配置 JSON。
     *
     * 两个 Provider 共享此方法：同一时间窗口内的多次调用只发出一次 HTTP 请求。
     *
     * @param forceRefresh true 时跳过内存缓存，使用主源优先策略
     * @return 完整的 JSON 配置对象；若所有源都失败则返回 null
     */
    suspend fun fetchConfig(forceRefresh: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        // 快速路径：检查内存缓存（无锁）
        if (!forceRefresh) {
            val now = System.currentTimeMillis()
            if (cachedJson != null && now - cachedAt < CACHE_TTL_MS) {
                Log.d(TAG, "Returning cached config (age=${now - cachedAt}ms)")
                return@withContext cachedJson
            }
        }

        // 请求去重：并发调用共享同一 HTTP 请求
        mutex.withLock {
            // 获取锁后再次检查（可能已有其他调用完成了获取）
            if (!forceRefresh) {
                val now = System.currentTimeMillis()
                if (cachedJson != null && now - cachedAt < CACHE_TTL_MS) {
                    Log.d(TAG, "Returning cached config after mutex (age=${now - cachedAt}ms)")
                    return@withLock cachedJson
                }
            }

            val result = if (forceRefresh) {
                fetchPrimaryFirst()
            } else {
                fetchWithRace()
            }

            if (result != null) {
                cachedJson = result
                cachedAt = System.currentTimeMillis()
                Log.i(TAG, "Config fetched and cached successfully")
            } else {
                Log.w(TAG, "All sources failed, config not updated")
            }
            result
        }
    }

    /**
     * 主源优先策略（forceRefresh=true 时使用）。
     *
     * 降级链路：Gitee API（ETag）→ GitHub raw → jsDelivr → Gitee raw（末位兜底）。
     */
    private suspend fun fetchPrimaryFirst(): JSONObject? {
        // 步骤1：Gitee API（主源，ETag 条件请求，不触发 raw 反爬）
        val apiResult = withTimeoutOrNull(PRIMARY_TIMEOUT_MS) {
            fetchFromGiteeApi()
        }
        if (apiResult != null) {
            Log.i(TAG, "Primary source (Gitee API) returned fresh config")
            return apiResult
        }
        Log.w(TAG, "Gitee API timed out or failed after ${PRIMARY_TIMEOUT_MS}ms, falling back to GitHub raw")

        // 步骤2：GitHub raw（官方直链，内容最新）
        val githubResult = fetchFromRaw(SOURCE_GITHUB_RAW, "GitHub raw")
        if (githubResult != null) {
            Log.i(TAG, "GitHub raw returned fresh config")
            return githubResult
        }
        Log.w(TAG, "GitHub raw failed, falling back to jsDelivr")

        // 步骤3：jsDelivr（CDN 缓存，可能返回旧版本）
        val jsdelivrResult = fetchFromRaw(SOURCE_JSDELIVR, "jsDelivr")
        if (jsdelivrResult != null) {
            Log.w(TAG, "jsDelivr returned config (WARNING: may be CDN-cached stale)")
            return jsdelivrResult
        }

        // 步骤4：Gitee raw（末位兜底，最小化 raw 请求频次）
        Log.w(TAG, "All sources failed, last resort: Gitee raw")
        return fetchFromRaw(SOURCE_GITEE_RAW, "Gitee raw", useReferer = true)
    }

    /**
     * 并行竞速策略（forceRefresh=false，启动自动检查时使用）。
     *
     * Gitee API 和 GitHub raw 并发请求，谁先成功用谁。
     * 两者都失败时降级到 jsDelivr，最后兜底 Gitee raw。
     */
    private suspend fun fetchWithRace(): JSONObject? = coroutineScope {
        val apiDeferred = async { fetchFromGiteeApi() }
        val githubDeferred = async { fetchFromRaw(SOURCE_GITHUB_RAW, "GitHub raw") }

        val firstResult = withTimeoutOrNull(RACE_TIMEOUT_MS) {
            select {
                apiDeferred.onAwait { it }
                githubDeferred.onAwait { it }
            }
        }

        if (firstResult != null) {
            Log.i(TAG, "Race winner returned valid config")
            return@coroutineScope firstResult
        }

        // 窗口内无结果，等待两个源最终完成
        Log.d(TAG, "No winner within ${RACE_TIMEOUT_MS}ms, awaiting both sources")
        val r1 = apiDeferred.await()
        if (r1 != null) return@coroutineScope r1

        val r2 = githubDeferred.await()
        if (r2 != null) return@coroutineScope r2

        // 降级 jsDelivr
        Log.w(TAG, "Both Gitee API and GitHub raw failed, falling back to jsDelivr")
        val jsdelivrResult = fetchFromRaw(SOURCE_JSDELIVR, "jsDelivr")
        if (jsdelivrResult != null) return@coroutineScope jsdelivrResult

        // 末位兜底：Gitee raw
        Log.w(TAG, "jsDelivr failed, last resort: Gitee raw")
        fetchFromRaw(SOURCE_GITEE_RAW, "Gitee raw", useReferer = true)
    }

    /**
     * 从 Gitee Open API 获取配置。
     *
     * API 返回 JSON 中 `content` 字段为 Base64 编码的文件内容。
     * 支持 ETag 条件请求：服务端返回 304 时直接使用内存缓存。
     */
    private fun fetchFromGiteeApi(): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            Log.d(TAG, "Fetching from Gitee API")
            conn = (URL(SOURCE_GITEE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Referer", GITEE_REFERER)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                // ETag 条件请求：有缓存 ETag 时携带 If-None-Match
                cachedEtag?.let { setRequestProperty("If-None-Match", it) }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            Log.d(TAG, "Gitee API response: $code")

            when (code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    // 304 Not Modified：内容未变，使用内存缓存
                    Log.d(TAG, "Gitee API: 304 Not Modified (ETag match), using cached config")
                    cachedJson
                }
                HttpURLConnection.HTTP_OK -> {
                    // 200 OK：保存 ETag，解析 Base64 内容
                    conn.getHeaderField("ETag")?.let { etag ->
                        cachedEtag = etag
                        Log.d(TAG, "Gitee API: saved ETag for conditional requests")
                    }

                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val apiResponse = JSONObject(body)
                    val content = apiResponse.optString("content").trim()
                    if (content.isEmpty()) {
                        Log.w(TAG, "Gitee API: empty content field")
                        return null
                    }

                    // Base64 解码（API 返回的 content 可能包含换行符，需移除）
                    val decoded = try {
                        Base64.decode(content.replace("\n", ""), Base64.DEFAULT)
                            .toString(Charsets.UTF_8)
                    } catch (e: Exception) {
                        Log.w(TAG, "Gitee API: base64 decode failed: ${e.message}")
                        return null
                    }

                    // 校验解码后内容是否为合法 JSON
                    val trimmed = decoded.trimStart()
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        Log.w(TAG, "Gitee API: decoded content is not JSON (head: ${trimmed.take(80)})")
                        return null
                    }

                    JSONObject(decoded)
                }
                403 -> {
                    Log.w(TAG, "Gitee API: 403 (rate limited or forbidden)")
                    null
                }
                else -> {
                    Log.w(TAG, "Gitee API: HTTP $code")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gitee API fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从 raw 链接获取配置（GitHub raw / jsDelivr / Gitee raw）。
     *
     * @param useReferer 为 Gitee raw 请求添加 Referer 头，降低被反爬拦截概率
     */
    private fun fetchFromRaw(urlString: String, sourceName: String, useReferer: Boolean = false): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            Log.d(TAG, "Fetching from $sourceName")
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                if (useReferer) {
                    setRequestProperty("Referer", GITEE_REFERER)
                }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            Log.d(TAG, "$sourceName response: $code")

            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$sourceName: HTTP $code")
                return null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }

            // 校验响应是否为 JSON（Gitee raw 可能返回 HTML 验证页）
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                Log.w(TAG, "$sourceName: non-JSON response (likely CAPTCHA/verification page, head: ${trimmed.take(80)})")
                return null
            }

            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "$sourceName fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
