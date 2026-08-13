package com.azurlane.blyy.util

import android.util.Log
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 * 复用 [NetworkDriveLinkProvider] 的设计模式（官方 API 多源策略 + 三级缓存），
 * 独立存储 SD 资源链接缓存，避免与应用更新网盘链接互相覆盖。
 * 获取逻辑统一委托 [RemoteJsonFetcher]（多源串行降级 + 反滥用安全策略）。
 *
 * ## 源策略（官方 API 主源优先 + 串行降级链路）
 *
 * | 优先级 | 源                        | 特点                                          |
 * |--------|---------------------------|-----------------------------------------------|
 * | 1      | Gitee Contents API       | 官方接口，合规不缓存，国内访问快，内容最新        |
 * | 2      | GitHub Contents API      | 官方接口，内容最新，但国内访问慢（4-5s）         |
 * | 3      | jsDelivr CDN             | 国内 CDN 加速，但有数小时缓存且无法绕过（兜底）   |
 *
 * **为什么改用官方 API 而不用 raw 直链**：
 * - raw 直链高频访问 + 缓存破坏参数（?_t=ts）是典型自动化特征，易被托管平台风控拦截
 *   （Access denied / 验证页）；官方 Contents API 合规、不缓存，配合浏览器化请求头更安全
 *
 * ## 及时性
 *
 * - 主源 Gitee Contents API 不缓存、每次回源，获取成功即拿到仓库最新链接
 * - 内存缓存 30 秒 TTL：避免短时间内重复请求，同时保证 30 秒内感知配置更新
 * - forceRefresh=true 时跳过内存缓存强制回源，拿到绝对最新
 *
 * ## 缓存策略（三级降级）
 * 1. 内存缓存（30 秒 TTL）：避免短时间内重复请求
 * 2. DataStore 持久化缓存（7 天最大有效期）：App 重启后兜底
 * 3. 在线获取：Gitee API → GitHub API → jsDelivr
 *
 * @param forceRefresh true 时跳过内存缓存强制在线获取（准确性优先）
 */
@Singleton
class SdResourceLinkProvider @Inject constructor(
    private val settings: PlayerSettingsDataStore,
    private val jsonFetcher: RemoteJsonFetcher
) {

    companion object {
        private const val TAG = "SdResourceLinkProvider"

        private const val GITEE_OWNER = "dreamweavers-whisper"
        private const val GITEE_REPO = "blyy"
        private const val GITEE_BRANCH = "master"
        private const val GITHUB_OWNER = "oneroomlife"
        private const val GITHUB_REPO = "blyy"
        private const val GITHUB_BRANCH = "main"
        private const val CONFIG_FILE = "network_drive_links.json"

        /**
         * 远程配置源（串行降级，主源在前）。与 [NetworkDriveLinkProvider] 同源：
         * SD 资源链接已整合进 network_drive_links.json，只需更新该文件即可同时更新
         * 应用网盘链接与 SD 资源链接，无需维护两份配置。
         *
         * 1. Gitee Contents API（官方接口，国内快且不缓存，内容最新）→ 主源，保证及时性
         * 2. GitHub Contents API（官方接口，内容最新，国内访问慢）→ 备用
         * 3. jsDelivr CDN（有缓存，仅最后兜底）→ 离线 / 慢网下的最后保障
         *
         * 不使用 raw 直链（详见类注释），由 [RemoteJsonFetcher] 统一执行安全获取策略。
         */
        private val SOURCES = listOf(
            RemoteJsonSource(
                url = "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/contents/$CONFIG_FILE?ref=$GITEE_BRANCH",
                encoding = RemoteJsonEncoding.BASE64,
                referer = "https://gitee.com/$GITEE_OWNER/$GITEE_REPO"
            ),
            RemoteJsonSource(
                url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$CONFIG_FILE?ref=$GITHUB_BRANCH",
                encoding = RemoteJsonEncoding.BASE64,
                referer = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
            ),
            RemoteJsonSource(
                url = "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@latest/$CONFIG_FILE",
                encoding = RemoteJsonEncoding.PLAIN
            )
        )

        /** 内存缓存有效期（毫秒）。30 秒内复用，超时自动回源，兼顾及时与节流 */
        private const val CACHE_TTL_MS = 30_000L

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

        // 在线获取最新链接（多源串行降级：Gitee API → GitHub API → jsDelivr）
        val winner = fetchOnline()

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
     * 在线获取最新 SD 资源链接。
     *
     * 委托 [RemoteJsonFetcher] 按优先级串行降级（Gitee API → GitHub API → jsDelivr），
     * 并统一执行反滥用安全策略（浏览器化请求头、限速退避、响应内容校验）。
     * 主源 Gitee Contents API 不缓存、每次回源，获取成功即拿到仓库最新链接。
     *
     * @return 解析成功的 SD 资源链接；所有源均失败时返回 null
     */
    private suspend fun fetchOnline(): SdResourceLink? {
        val json = jsonFetcher.fetchWithFallback(SOURCES) ?: return null
        return parseJson(json)
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
