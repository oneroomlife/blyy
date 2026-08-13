package com.azurlane.blyy.util

import android.util.Log
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SD 资源下载链接（在线获取，与应用更新网盘链接同源，来自代码仓库 network_drive_links.json）。
 *
 * @param url   网盘下载地址（含提取码参数，如 https://pan.quark.cn/s/xxx?pwd=yyyy）
 * @param label 网盘类型名称（如 "夸克网盘"）
 * @param note  备注说明（如"下载 SD 小人资源包"）
 */
data class SdResourceLink(
    val url: String,
    val label: String,
    val note: String = ""
)

/**
 * SD 资源下载链接在线获取器。
 *
 * 从代码仓库的 `network_drive_links.json` 文件动态获取 SD 小人资源包下载链接。
 * 该文件同时承载应用更新网盘链接与 SD 资源下载链接（见 [NetworkDriveLinkProvider]），
 * 本类只读取其中的 `sd_*` 前缀字段，不互相干扰。
 * ```
 * {
 *   "pan_url": "https://pan.quark.cn/s/xxx?pwd=yyyy",     // 应用更新网盘链接（NetworkDriveLinkProvider 读取）
 *   "pan_type": "夸克网盘",
 *   "notes": "若 GitHub 下载缓慢，可使用网盘下载。",
 *   "sd_pan_url": "https://pan.quark.cn/s/zzz?pwd=aaaa",  // SD 资源下载链接（本类读取）
 *   "sd_pan_type": "夸克网盘",
 *   "sd_notes": "下载 SD 小人资源包"
 * }
 * ```
 *
 * ## 设计参考
 *
 * 复用 [NetworkDriveLinkProvider] 的设计模式（多源策略 + 三级缓存），
 * 独立存储 SD 资源链接缓存，避免与应用更新网盘链接互相覆盖。
 *
 * ## 源策略（Gitee 主源优先 + 降级链路）
 *
 * | 优先级 | 源              | 特点                                              | CDN 缓存 |
 * |--------|-----------------|---------------------------------------------------|----------|
 * | 1      | Gitee raw       | 国内代码托管，访问快（0.5-1.5s），不缓存，内容最新   | 无       |
 * | 2      | GitHub raw      | GitHub 官方直链，内容最新，但国内访问慢（4-5s）      | 无       |
 * | 3      | jsDelivr CDN    | 国内 CDN 加速，但有数小时缓存且无法绕过              | 有       |
 *
 * ## 双模式获取
 *
 * 1. **启动自动检查**（forceRefresh=false）：
 *    - Gitee 与 GitHub raw 并行竞速，谁先成功用谁（速度优先）
 *    - 两者都失败时降级到 jsDelivr（可能拿到 CDN 缓存的旧版本）
 *
 * 2. **用户主动触发**（forceRefresh=true）：
 *    - Gitee 主源优先（快且最新），等待 [PRIMARY_TIMEOUT_MS] 窗口
 *    - Gitee 超时/失败 → 降级 GitHub raw（内容最新但慢）
 *    - GitHub raw 也失败 → 降级 jsDelivr（最后兜底）
 *
 * ## 缓存策略（三级降级）
 * 1. 内存缓存（30 秒 TTL）：避免短时间内重复请求
 * 2. DataStore 持久化缓存（7 天最大有效期）：App 重启后兜底
 * 3. 在线获取：Gitee → GitHub raw → jsDelivr
 *
 * @param forceRefresh true 时跳过内存缓存，且使用主源优先策略（准确性优先）
 */
@Singleton
class SdResourceLinkProvider @Inject constructor(
    private val settings: PlayerSettingsDataStore
) {

    companion object {
        private const val TAG = "SdResourceLinkProvider"

        /**
         * 源 1（主源）：Gitee raw（国内代码托管，访问快 0.5-1.5s，不缓存，内容最新）。
         * 与 [NetworkDriveLinkProvider] 同源：SD 资源链接已整合进 network_drive_links.json，
         * 只需更新该文件即可同时更新应用网盘链接与 SD 资源链接，无需维护两份配置。
         */
        private const val SOURCE_GITEE =
            "https://gitee.com/dreamweavers-whisper/blyy/raw/master/network_drive_links.json"

        /** 源 2：GitHub raw（官方直链，内容最新，但国内访问慢 4-5s） */
        private const val SOURCE_GITHUB_RAW =
            "https://raw.githubusercontent.com/oneroomlife/blyy/main/network_drive_links.json"

        /** 源 3（末位降级）：jsDelivr CDN（国内 CDN 加速，但有数小时缓存且无法绕过） */
        private const val SOURCE_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/oneroomlife/blyy@latest/network_drive_links.json"

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000

        /** 内存缓存有效期（毫秒），避免短时间内重复请求 */
        private const val CACHE_TTL_MS = 30_000L

        /** 主源（Gitee）优先等待窗口（毫秒）。forceRefresh=true 时优先等待 Gitee 返回 */
        private const val PRIMARY_TIMEOUT_MS = 4_000L

        /** 启动竞速模式总超时（毫秒） */
        private const val RACE_TIMEOUT_MS = 5_000L

        /** 持久化缓存最大有效期（毫秒）。7 天：网盘链接可能因分享过期而失效 */
        private const val PERSISTED_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        /**
         * 内置默认下载链接（最后兜底）。
         *
         * 当所有远程源（Gitee/GitHub/jsDelivr）和本地缓存（内存/DataStore）都失败时使用，
         * 确保 SD 资源下载入口在任何网络环境下都不会失效（表现为"获取失败"）。
         *
         * 注意：此兜底仅在离线/仓库文件缺失时生效，正常联网时会优先使用远程仓库
         * `network_drive_links.json` 中的 `sd_*` 字段最新链接（远程链接可动态更新，无需发版）。
         */
        private const val DEFAULT_FALLBACK_URL = "https://pan.quark.cn/s/ed497182ee99?pwd=hNJx"
        private const val DEFAULT_FALLBACK_LABEL = "夸克网盘"
    }

    /** 缓存的解析结果 */
    @Volatile
    private var cachedLink: SdResourceLink? = null

    /** 缓存时间戳（毫秒） */
    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 在线获取 SD 资源下载链接。
     *
     * @param forceRefresh true 时跳过内存缓存，且使用主源优先策略（确保拿到最新链接）
     * @return SD 资源链接；若获取失败且无任何缓存则返回 null
     */
    suspend fun getLink(forceRefresh: Boolean = false): SdResourceLink? = withContext(Dispatchers.IO) {
        // 检查内存缓存是否有效
        if (!forceRefresh) {
            val now = System.currentTimeMillis()
            if (cachedLink != null && now - cachedAt < CACHE_TTL_MS) {
                Log.d(TAG, "Returning cached SD resource link (age=${now - cachedAt}ms, label=${cachedLink?.label})")
                return@withContext cachedLink
            }
        }

        // 根据场景选择获取策略
        val winner = if (forceRefresh) {
            fetchPrimaryFirst()
        } else {
            fetchWithRace()
        }

        if (winner != null) {
            cachedLink = winner
            cachedAt = System.currentTimeMillis()
            // 持久化缓存：写入 DataStore 作为跨重启兜底
            try {
                settings.setCachedSdResourceLink(serializeLink(winner))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist SD resource link cache: ${e.message}")
            }
            return@withContext winner
        }

        // 所有在线源都失败：依次降级到过期内存缓存 → DataStore 持久化缓存
        if (cachedLink != null) {
            Log.w(TAG, "All online sources failed, returning stale in-memory cache (label=${cachedLink?.label})")
            return@withContext cachedLink
        }

        try {
            val persisted = settings.getCachedSdResourceLink()
            if (persisted != null) {
                val age = System.currentTimeMillis() - persisted.second
                if (age > PERSISTED_CACHE_MAX_AGE_MS) {
                    Log.w(TAG, "Persisted SD resource cache is too old (age=${age}ms > max=$PERSISTED_CACHE_MAX_AGE_MS), discarding")
                    settings.clearCachedSdResourceLink()
                } else {
                    val restored = deserializeLink(persisted.first)
                    if (restored != null) {
                        Log.w(TAG, "All online sources failed, returning persisted DataStore cache (age=${age}ms, label=${restored.label})")
                        cachedLink = restored
                        cachedAt = persisted.second
                        return@withContext restored
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read persisted SD resource link cache: ${e.message}")
        }

        // 所有远程源和缓存都失败：回退到内置默认链接（最后兜底），
        // 确保下载入口永不失效。此兜底不写入缓存，下次仍会尝试远程获取最新链接。
        Log.w(TAG, "All sources failed and no cache available, using built-in default fallback")
        SdResourceLink(
            url = DEFAULT_FALLBACK_URL,
            label = DEFAULT_FALLBACK_LABEL,
            note = "下载 SD 小人资源包"
        )
    }

    /**
     * 主源优先策略（forceRefresh=true 时使用）。
     *
     * 降级链路：Gitee（主源，快且最新）→ GitHub raw（最新但慢）→ jsDelivr（有缓存，最后兜底）。
     */
    private suspend fun fetchPrimaryFirst(): SdResourceLink? {
        // 步骤1：优先等待 Gitee 主源（国内快且不缓存，内容最新）
        val giteeResult = withTimeoutOrNull(PRIMARY_TIMEOUT_MS) {
            fetchAndParse(buildCacheBustingUrl(SOURCE_GITEE))
        }
        if (giteeResult != null) {
            Log.i(TAG, "Primary source (Gitee) returned fresh SD resource link within ${PRIMARY_TIMEOUT_MS}ms")
            return giteeResult
        }
        Log.w(TAG, "Primary source (Gitee) timed out or failed after ${PRIMARY_TIMEOUT_MS}ms, falling back to GitHub raw")

        // 步骤2：降级 GitHub raw（官方源，内容最新，但国内访问慢）
        val githubResult = fetchAndParse(buildCacheBustingUrl(SOURCE_GITHUB_RAW))
        if (githubResult != null) {
            Log.i(TAG, "GitHub raw returned fresh SD resource link")
            return githubResult
        }
        Log.w(TAG, "GitHub raw failed, falling back to jsDelivr (may be stale)")

        // 步骤3：最后降级 jsDelivr（有 CDN 缓存，可能返回旧版本，仅作兜底）
        val jsdelivrResult = fetchAndParse(buildCacheBustingUrl(SOURCE_JSDELIVR))
        if (jsdelivrResult != null) {
            Log.w(TAG, "jsDelivr returned SD resource link (WARNING: may be CDN-cached stale, label=${jsdelivrResult.label})")
        }
        return jsdelivrResult
    }

    /**
     * 并行竞速策略（forceRefresh=false，启动自动检查时使用）。
     *
     * Gitee 和 GitHub raw 并发请求，谁先成功用谁。
     * 两者都失败时降级到 jsDelivr。
     */
    private suspend fun fetchWithRace(): SdResourceLink? = coroutineScope {
        val giteeDeferred = async { fetchAndParse(buildCacheBustingUrl(SOURCE_GITEE)) }
        val githubDeferred = async { fetchAndParse(buildCacheBustingUrl(SOURCE_GITHUB_RAW)) }

        val firstResult = withTimeoutOrNull(RACE_TIMEOUT_MS) {
            select {
                giteeDeferred.onAwait { it }
                githubDeferred.onAwait { it }
            }
        }

        if (firstResult != null) {
            Log.i(TAG, "Race winner returned a valid SD resource link (label=${firstResult.label})")
            return@coroutineScope firstResult
        }

        Log.d(TAG, "No winner within ${RACE_TIMEOUT_MS}ms, awaiting both sources")
        val r1 = giteeDeferred.await()
        if (r1 != null) return@coroutineScope r1

        val r2 = githubDeferred.await()
        if (r2 != null) return@coroutineScope r2

        Log.w(TAG, "Both Gitee and GitHub raw failed, falling back to jsDelivr")
        fetchAndParse(buildCacheBustingUrl(SOURCE_JSDELIVR))
    }

    /**
     * 构造带缓存破坏时间戳参数的 URL，绕过 CDN/代理层缓存。
     */
    private fun buildCacheBustingUrl(baseUrl: String): String {
        val ts = System.currentTimeMillis()
        val separator = if (baseUrl.contains('?')) '&' else '?'
        return "$baseUrl${separator}_t=$ts"
    }

    /**
     * 从指定 URL 获取并解析 SD 资源链接。
     */
    private fun fetchAndParse(urlString: String): SdResourceLink? {
        var connection: HttpURLConnection? = null
        return try {
            Log.d(TAG, "Fetching from: $urlString")
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            // 使用浏览器 UA：Gitee raw 对非浏览器 UA 的自动化请求可能返回验证页（CAPTCHA），
            // 浏览器 UA 可显著降低被拦截概率，确保拿到真正的 JSON 配置而非验证页 HTML
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode from ${urlString.substringBefore("?")}")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $responseCode from ${urlString.substringBefore("?")}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }

            // 诊断：Gitee 可能对自动化请求返回 HTML 验证页（HTTP 200 但内容非 JSON）。
            // 若响应不是以 JSON 对象/数组开头，说明拿到的是验证页/错误页而非配置，明确记录以便排查。
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                Log.w(
                    TAG,
                    "Non-JSON response from ${urlString.substringBefore("?")}: " +
                        "likely CAPTCHA/verification page or missing file (body head: ${trimmed.take(80)})"
                )
                return null
            }

            parseJson(body)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch from ${urlString.substringBefore("?")}: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析 JSON 配置为 [SdResourceLink]。
     *
     * 从整合后的 network_drive_links.json 中读取 `sd_*` 前缀字段（与 [NetworkDriveLinkProvider]
     * 读取的 `pan_url` 等字段互不干扰）：
     * ```
     * {
     *   "pan_url": "...",     // 应用更新网盘链接（NetworkDriveLinkProvider 读取，忽略）
     *   "sd_pan_url": "https://pan.quark.cn/s/xxx?pwd=yyyy",
     *   "sd_pan_type": "夸克网盘",
     *   "sd_notes": "下载 SD 小人资源包"
     * }
     * ```
     */
    private fun parseJson(json: String): SdResourceLink? {
        return try {
            val root = JSONObject(json)
            val url = root.optString("sd_pan_url").trim()
            if (!isValidUrl(url)) {
                Log.w(TAG, "Invalid or missing sd_pan_url: $url")
                return null
            }
            val label = root.optString("sd_pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("sd_notes").trim()
            Log.i(TAG, "Parsed SD resource link: label=$label, url=$url")
            SdResourceLink(
                url = url,
                label = label,
                note = note
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
            null
        }
    }

    /** 将 [SdResourceLink] 序列化为 JSON 字符串，用于持久化到 DataStore（使用 sd_ 前缀保持语义清晰） */
    private fun serializeLink(link: SdResourceLink): String {
        return JSONObject().apply {
            put("sd_pan_url", link.url)
            put("sd_pan_type", link.label)
            put("sd_notes", link.note)
        }.toString()
    }

    /** 将 DataStore 中持久化的 JSON 反序列化为 [SdResourceLink] */
    private fun deserializeLink(json: String): SdResourceLink? {
        return try {
            val root = JSONObject(json)
            val url = root.optString("sd_pan_url").trim()
            if (!isValidUrl(url)) return null
            val label = root.optString("sd_pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("sd_notes").trim()
            SdResourceLink(
                url = url,
                label = label,
                note = note
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize persisted SD resource link: ${e.message}")
            null
        }
    }

    /**
     * 校验 URL 合法性：必须是 http/https 协议且能被 java.net.URL 解析。
     */
    private fun isValidUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        return try {
            val parsed = URL(url)
            parsed.protocol == "http" || parsed.protocol == "https"
        } catch (e: MalformedURLException) {
            false
        }
    }
}
