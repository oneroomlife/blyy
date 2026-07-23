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
 * 网盘下载链接（在线获取，来自代码仓库 network_drive_links.json）。
 *
 * @param url     网盘下载地址（含提取码参数，如 https://pan.quark.cn/s/xxx?pwd=yyyy）
 * @param label   网盘类型名称（如 "夸克网盘"）
 * @param note    备注说明（如 "若 GitHub 下载缓慢，可使用网盘下载。"）
 * @param version 配置版本号（递增整数，用于持久化缓存新旧判断）。无此字段时为 -1
 */
data class NetworkDriveLink(
    val url: String,
    val label: String,
    val note: String = "",
    val version: Long = -1L
)

/**
 * 网盘更新链接在线获取器。
 *
 * 从代码仓库的 network_drive_links.json 文件动态获取网盘链接：
 * { "version": 2, "pan_url": "...", "pan_type": "...", "notes": "..." }
 *
 * ## 源策略（Gitee 主源优先 + 降级链路）
 *
 * | 优先级 | 源                          | 特点                                          | CDN 缓存 |
 * |--------|-----------------------------|-----------------------------------------------|----------|
 * | 1      | Gitee raw                   | 国内代码托管，访问快（0.5-1.5s），不缓存，内容最新 | 无       |
 * | 2      | GitHub raw                  | GitHub 官方直链，内容最新，但国内访问慢（4-5s）   | 无       |
 * | 3      | jsDelivr CDN                | 国内 CDN 加速，但有数小时缓存且无法绕过           | 有       |
 *
 * **为什么选 Gitee 作为主源**：
 * - Gitee 是国内代码托管平台，访问速度快且稳定，无需翻墙
 * - raw 链接直接回源，不被 CDN 缓存，确保返回最新内容
 * - 彻底解决 jsDelivr CDN 缓存导致的"GitHub 已更新但 App 拿到旧链接"问题
 *
 * ## 双模式获取
 *
 * 1. **启动自动检查**（forceRefresh=false）：
 *    - Gitee 与 GitHub raw 并行竞速，谁先成功用谁（速度优先）
 *    - 两者都失败时降级到 jsDelivr（可能拿到 CDN 缓存的旧版本）
 *    - 启动时拿到旧链接可接受，用户点击"网盘更新"时会强制刷新拿到最新
 *
 * 2. **用户主动触发**（forceRefresh=true）：
 *    - Gitee 主源优先（快且最新），等待 [PRIMARY_TIMEOUT_MS] 窗口
 *    - Gitee 超时/失败 → 降级 GitHub raw（内容最新但慢）
 *    - GitHub raw 也失败 → 降级 jsDelivr（最后兜底）
 *    - **绝不显示旧链接**：Gitee 和 GitHub raw 都不缓存，返回的永远是最新内容
 *
 * ## 缓存策略（三级降级）
 * 1. 内存缓存（30 秒 TTL）：避免短时间内重复请求
 * 2. DataStore 持久化缓存（7 天最大有效期）：App 重启后兜底
 * 3. 在线获取：Gitee → GitHub raw → jsDelivr
 *
 * @param forceRefresh true 时跳过内存缓存，且使用主源优先策略（准确性优先）
 */
@Singleton
class NetworkDriveLinkProvider @Inject constructor(
    private val settings: PlayerSettingsDataStore
) {

    companion object {
        private const val TAG = "NetworkDriveLinkProvider"

        /** 源 1（主源）：Gitee raw（国内代码托管，访问快 0.5-1.5s，不缓存，内容最新）。
         *  Gitee raw 链接格式：https://gitee.com/{owner}/{repo}/raw/{branch}/{path} */
        private const val SOURCE_GITEE =
            "https://gitee.com/dreamweavers-whisper/blyy/raw/master/network_drive_links.json"

        /** 源 2：GitHub raw（官方直链，内容最新，但国内访问慢 4-5s） */
        private const val SOURCE_GITHUB_RAW =
            "https://raw.githubusercontent.com/oneroomlife/blyy/main/network_drive_links.json"

        /** 源 3（末位降级）：jsDelivr CDN（国内 CDN 加速，但有数小时缓存且无法绕过）。
         *  仅作为 Gitee 和 GitHub raw 都失败时的最后兜底，可能返回 CDN 缓存的旧版本 */
        private const val SOURCE_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/oneroomlife/blyy@latest/network_drive_links.json"

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000

        /** 内存缓存有效期（毫秒），避免短时间内重复请求 */
        private const val CACHE_TTL_MS = 30_000L

        /** 主源（Gitee）优先等待窗口（毫秒）。forceRefresh=true 时优先等待 Gitee 返回。
         *  设为 4 秒：Gitee 正常 0.5-1.5s 返回，4 秒窗口足够覆盖网络波动 */
        private const val PRIMARY_TIMEOUT_MS = 4_000L

        /** 启动竞速模式总超时（毫秒）。Gitee 和 GitHub raw 并发，超过此时长仍未有结果则降级 jsDelivr */
        private const val RACE_TIMEOUT_MS = 5_000L

        /** 持久化缓存最大有效期（毫秒）。超过此时间的持久化缓存视为过期，不作为降级兜底。
         *  7 天：网盘链接可能因分享过期而失效，太久远的缓存不可靠 */
        private const val PERSISTED_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }

    /** 缓存的解析结果 */
    @Volatile
    private var cachedLink: NetworkDriveLink? = null

    /** 缓存时间戳（毫秒） */
    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 在线获取网盘下载链接。
     *
     * @param forceRefresh true 时跳过内存缓存，且使用主源优先策略（确保拿到最新链接）
     * @return 网盘链接；若获取失败且无任何缓存则返回 null
     */
    suspend fun getLink(forceRefresh: Boolean = false): NetworkDriveLink? = withContext(Dispatchers.IO) {
        // 检查内存缓存是否有效
        if (!forceRefresh) {
            val now = System.currentTimeMillis()
            if (cachedLink != null && now - cachedAt < CACHE_TTL_MS) {
                Log.d(TAG, "Returning cached drive link (age=${now - cachedAt}ms, version=${cachedLink?.version})")
                return@withContext cachedLink
            }
        }

        // 根据场景选择获取策略
        val winner = if (forceRefresh) {
            // 用户主动触发：主源（Gitee）优先，确保拿到最新链接
            fetchPrimaryFirst()
        } else {
            // 启动自动检查：Gitee + GitHub raw 并行竞速，速度优先
            fetchWithRace()
        }

        if (winner != null) {
            cachedLink = winner
            cachedAt = System.currentTimeMillis()
            // 持久化缓存：写入 DataStore 作为跨重启兜底
            try {
                settings.setCachedDriveLink(serializeLink(winner))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist drive link cache: ${e.message}")
            }
            return@withContext winner
        }

        // 所有在线源都失败：依次降级到过期内存缓存 → DataStore 持久化缓存
        if (cachedLink != null) {
            Log.w(TAG, "All online sources failed, returning stale in-memory cache (version=${cachedLink?.version})")
            return@withContext cachedLink
        }

        try {
            val persisted = settings.getCachedDriveLink()
            if (persisted != null) {
                val age = System.currentTimeMillis() - persisted.second
                if (age > PERSISTED_CACHE_MAX_AGE_MS) {
                    Log.w(TAG, "Persisted cache is too old (age=${age}ms > max=$PERSISTED_CACHE_MAX_AGE_MS), discarding")
                    settings.clearCachedDriveLink()
                } else {
                    val restored = deserializeLink(persisted.first)
                    if (restored != null) {
                        Log.w(TAG, "All online sources failed, returning persisted DataStore cache (age=${age}ms, version=${restored.version})")
                        cachedLink = restored
                        cachedAt = persisted.second
                        return@withContext restored
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read persisted drive link cache: ${e.message}")
        }

        Log.w(TAG, "All sources failed and no cache available")
        null
    }

    /**
     * 主源优先策略（forceRefresh=true 时使用）。
     *
     * 降级链路：Gitee（主源，快且最新）→ GitHub raw（最新但慢）→ jsDelivr（有缓存，最后兜底）。
     *
     * - Gitee 在 [PRIMARY_TIMEOUT_MS] 窗口内返回 → 用 Gitee 结果（最快且最新）
     * - Gitee 超时/失败 → 尝试 GitHub raw（内容最新，但国内慢）
     * - GitHub raw 也失败 → 降级 jsDelivr（可能拿到 CDN 缓存的旧版本，但至少有链接）
     *
     * Gitee 和 GitHub raw 都不缓存，返回的永远是仓库中的最新内容，**绝不显示旧链接**。
     */
    private suspend fun fetchPrimaryFirst(): NetworkDriveLink? {
        // 步骤1：优先等待 Gitee 主源（国内快且不缓存，内容最新）
        val giteeResult = withTimeoutOrNull(PRIMARY_TIMEOUT_MS) {
            fetchAndParse(buildCacheBustingUrl(SOURCE_GITEE))
        }
        if (giteeResult != null) {
            Log.i(TAG, "Primary source (Gitee) returned fresh link within ${PRIMARY_TIMEOUT_MS}ms")
            return giteeResult
        }
        Log.w(TAG, "Primary source (Gitee) timed out or failed after ${PRIMARY_TIMEOUT_MS}ms, falling back to GitHub raw")

        // 步骤2：降级 GitHub raw（官方源，内容最新，但国内访问慢）
        val githubResult = fetchAndParse(buildCacheBustingUrl(SOURCE_GITHUB_RAW))
        if (githubResult != null) {
            Log.i(TAG, "GitHub raw returned fresh link")
            return githubResult
        }
        Log.w(TAG, "GitHub raw failed, falling back to jsDelivr (may be stale)")

        // 步骤3：最后降级 jsDelivr（有 CDN 缓存，可能返回旧版本，仅作兜底）
        val jsdelivrResult = fetchAndParse(buildCacheBustingUrl(SOURCE_JSDELIVR))
        if (jsdelivrResult != null) {
            Log.w(TAG, "jsDelivr returned link (WARNING: may be CDN-cached stale version=${jsdelivrResult.version})")
        }
        return jsdelivrResult
    }

    /**
     * 并行竞速策略（forceRefresh=false，启动自动检查时使用）。
     *
     * Gitee 和 GitHub raw 并发请求，谁先成功用谁。
     * 两者都失败时降级到 jsDelivr。
     *
     * 速度优先：Gitee 国内通常 0.5-1.5s 返回（最快且最新）。
     * 启动时拿到最新链接最佳，即使拿到旧链接也可接受（用户点击"网盘更新"时会强制刷新）。
     */
    private suspend fun fetchWithRace(): NetworkDriveLink? = coroutineScope {
        val giteeDeferred = async { fetchAndParse(buildCacheBustingUrl(SOURCE_GITEE)) }
        val githubDeferred = async { fetchAndParse(buildCacheBustingUrl(SOURCE_GITHUB_RAW)) }

        // select 返回第一个完成的结果（可能为 null 表示该源失败）
        val firstResult = withTimeoutOrNull(RACE_TIMEOUT_MS) {
            select {
                giteeDeferred.onAwait { it }
                githubDeferred.onAwait { it }
            }
        }

        if (firstResult != null) {
            Log.i(TAG, "Race winner returned a valid link (version=${firstResult.version})")
            return@coroutineScope firstResult
        }

        // 窗口内无结果，等待两个源最终完成
        Log.d(TAG, "No winner within ${RACE_TIMEOUT_MS}ms, awaiting both sources")
        val r1 = giteeDeferred.await()
        if (r1 != null) return@coroutineScope r1

        val r2 = githubDeferred.await()
        if (r2 != null) return@coroutineScope r2

        // 两个非缓存源都失败，降级 jsDelivr
        Log.w(TAG, "Both Gitee and GitHub raw failed, falling back to jsDelivr")
        fetchAndParse(buildCacheBustingUrl(SOURCE_JSDELIVR))
    }

    /**
     * 构造带缓存破坏时间戳参数的 URL，绕过 CDN/代理层缓存。
     * 原始 URL 无查询参数时追加 `?_t=ts`，已有查询参数时追加 `&_t=ts`。
     * 注意：jsDelivr 会忽略此参数，仍返回缓存；Gitee raw 和 GitHub raw 会尊重此参数。
     */
    private fun buildCacheBustingUrl(baseUrl: String): String {
        val ts = System.currentTimeMillis()
        val separator = if (baseUrl.contains('?')) '&' else '?'
        return "$baseUrl${separator}_t=$ts"
    }

    /**
     * 从指定 URL 获取并解析网盘链接。
     */
    private fun fetchAndParse(urlString: String): NetworkDriveLink? {
        var connection: HttpURLConnection? = null
        return try {
            Log.d(TAG, "Fetching from: $urlString")
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", "BLYY-Android-App")
            // 反 CDN 缓存：强制中间层不返回缓存响应，必须回源
            // 注意：jsDelivr 会忽略此头，仍返回缓存；Gitee raw 和 GitHub raw 会尊重此头
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

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            parseJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch from ${urlString.substringBefore("?")}: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析 JSON 配置为 [NetworkDriveLink]。
     * 支持可选的 version 字段（用于持久化缓存新旧判断）。
     */
    private fun parseJson(json: String): NetworkDriveLink? {
        return try {
            val root = JSONObject(json)
            val url = root.optString("pan_url").trim()
            if (!isValidUrl(url)) {
                Log.w(TAG, "Invalid or missing pan_url: $url")
                return null
            }
            val label = root.optString("pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("notes").trim()
            val version = root.optLong("version", -1L)
            Log.i(TAG, "Parsed drive link: label=$label, version=$version, url=$url")
            NetworkDriveLink(url = url, label = label, note = note, version = version)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
            null
        }
    }

    /** 将 [NetworkDriveLink] 序列化为 JSON 字符串，用于持久化到 DataStore */
    private fun serializeLink(link: NetworkDriveLink): String {
        return JSONObject().apply {
            put("pan_url", link.url)
            put("pan_type", link.label)
            put("notes", link.note)
            put("version", link.version)
        }.toString()
    }

    /** 将 DataStore 中持久化的 JSON 反序列化为 [NetworkDriveLink] */
    private fun deserializeLink(json: String): NetworkDriveLink? {
        return try {
            val root = JSONObject(json)
            val url = root.optString("pan_url").trim()
            if (!isValidUrl(url)) return null
            val label = root.optString("pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("notes").trim()
            val version = root.optLong("version", -1L)
            NetworkDriveLink(url = url, label = label, note = note, version = version)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize persisted link: ${e.message}")
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
