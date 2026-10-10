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
 * Live2D 模型资源下载链接（在线获取，与应用更新网盘链接同源，来自代码仓库 network_drive_links.json）。
 *
 * @param url   网盘下载地址（含提取码参数，如 https://pan.quark.cn/s/xxx?pwd=yyyy）
 * @param label 网盘类型名称（如 "夸克网盘"）
 * @param note  备注说明（如"下载 Live2D 模型资源包"）
 */
data class Live2dResourceLink(
    val url: String,
    val label: String,
    val note: String = ""
)

/**
 * Live2D 模型资源下载链接在线获取器。
 *
 * 从代码仓库的 `network_drive_links.json` 文件动态获取 Live2D 模型资源包下载链接。
 * 该文件同时承载应用更新网盘链接（`pan_*`）、SD 资源链接（`sd_*`，见
 * [SdResourceLinkProvider]）与 Live2D 资源链接，本类只读取其中的 `live2d_*` 前缀字段。
 *
 * ## 架构
 *
 * HTTP 请求和缓存由 [RemoteConfigFetcher] 统一管理：
 * - 多个 Provider（本类 / [NetworkDriveLinkProvider] / [SdResourceLinkProvider]）共享同一次 HTTP 请求
 * - 主源使用 Gitee Open API（非 raw 链接），避免被仓库判定为滥用 raw
 * - 支持 ETag 条件请求和请求去重
 *
 * 本类仅负责从完整 JSON 中解析 `live2d_*` 前缀字段，并管理自己的 DataStore 离线缓存。
 *
 * ## 缓存策略
 * 1. [RemoteConfigFetcher] 内存缓存（60 秒 TTL）：多个 Provider 共享
 * 2. DataStore 持久化缓存（7 天最大有效期）：App 重启后离线兜底
 * 3. 内置默认链接（最后兜底）：所有远程源和缓存都失败时使用
 */
@Singleton
class Live2dResourceLinkProvider @Inject constructor(
    private val fetcher: RemoteConfigFetcher,
    private val settings: PlayerSettingsDataStore
) {

    companion object {
        private const val TAG = "Live2dResourceLinkProvider"

        /** 持久化缓存最大有效期（毫秒）。7 天：网盘链接可能因分享过期而失效 */
        private const val PERSISTED_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        /**
         * 内置默认下载链接（占位符，最后兜底）。
         *
         * 当所有远程源（Gitee API/raw、GitHub、jsDelivr）和本地缓存都失败时使用。
         * 正常联网时优先使用远程仓库 `network_drive_links.json` 中 `live2d_*` 字段
         * 的最新链接（远程链接可动态更新，无需发版）。
         */
        private const val DEFAULT_FALLBACK_URL = "https://pan.quark.cn/s/000000000000?pwd=live2d"
        private const val DEFAULT_FALLBACK_LABEL = "夸克网盘"
    }

    /**
     * 在线获取 Live2D 模型资源下载链接。
     *
     * @param forceRefresh true 时跳过缓存，确保拿到最新链接
     * @return Live2D 资源链接；若获取失败且无任何缓存则返回内置默认链接
     */
    suspend fun getLink(forceRefresh: Boolean = false): Live2dResourceLink? = withContext(Dispatchers.IO) {
        // 通过共享获取层拿到完整 JSON 配置（多个 Provider 共享一次 HTTP 请求）
        val config = fetcher.fetchConfig(forceRefresh)
        if (config != null) {
            val link = parseConfig(config)
            if (link != null) {
                // 持久化到 DataStore 作为跨重启兜底
                try {
                    settings.setCachedLive2dResourceLink(serializeLink(link))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist Live2D resource link cache: ${e.message}")
                }
                return@withContext link
            }
        }

        // 在线获取失败：降级到 DataStore 持久化缓存
        try {
            val persisted = settings.getCachedLive2dResourceLink()
            if (persisted != null) {
                val age = System.currentTimeMillis() - persisted.second
                if (age > PERSISTED_CACHE_MAX_AGE_MS) {
                    Log.w(TAG, "Persisted Live2D resource cache is too old (age=${age}ms), discarding")
                    settings.clearCachedLive2dResourceLink()
                } else {
                    val restored = deserializeLink(persisted.first)
                    if (restored != null) {
                        Log.w(TAG, "Config fetch failed, returning persisted DataStore cache (age=${age}ms, label=${restored.label})")
                        return@withContext restored
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read persisted Live2D resource link cache: ${e.message}")
        }

        // 所有远程源和缓存都失败：回退到内置默认链接（最后兜底）
        Log.w(TAG, "All sources failed and no cache available, using built-in default fallback")
        Live2dResourceLink(
            url = DEFAULT_FALLBACK_URL,
            label = DEFAULT_FALLBACK_LABEL,
            note = "下载 Live2D 模型资源包"
        )
    }

    /**
     * 从完整 JSON 配置中解析 Live2D 资源链接字段（`live2d_pan_url` / `live2d_pan_type` / `live2d_notes`）。
     */
    private fun parseConfig(root: JSONObject): Live2dResourceLink? {
        return try {
            val url = root.optString("live2d_pan_url").trim()
            if (!isValidUrl(url)) {
                Log.w(TAG, "Invalid or missing live2d_pan_url: $url")
                return null
            }
            val label = root.optString("live2d_pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("live2d_notes").trim()
            Log.i(TAG, "Parsed Live2D resource link: label=$label, url=$url")
            Live2dResourceLink(
                url = url,
                label = label,
                note = note
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config: ${e.message}", e)
            null
        }
    }

    /** 将 [Live2dResourceLink] 序列化为 JSON 字符串，用于持久化到 DataStore */
    private fun serializeLink(link: Live2dResourceLink): String {
        return JSONObject().apply {
            put("live2d_pan_url", link.url)
            put("live2d_pan_type", link.label)
            put("live2d_notes", link.note)
        }.toString()
    }

    /** 将 DataStore 中持久化的 JSON 反序列化为 [Live2dResourceLink] */
    private fun deserializeLink(json: String): Live2dResourceLink? {
        return try {
            val root = JSONObject(json)
            val url = root.optString("live2d_pan_url").trim()
            if (!isValidUrl(url)) return null
            val label = root.optString("live2d_pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("live2d_notes").trim()
            Live2dResourceLink(
                url = url,
                label = label,
                note = note
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize persisted Live2D resource link: ${e.message}")
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
