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
 * 网盘下载链接（在线获取，来自代码仓库 network_drive_links.json）。
 *
 * @param url   网盘下载地址（含提取码参数，如 https://pan.quark.cn/s/xxx?pwd=yyyy）
 * @param label 网盘类型名称（如 "夸克网盘"）
 * @param note  备注说明（如 "若 GitHub 下载缓慢，可使用网盘下载。"）
 */
data class NetworkDriveLink(
    val url: String,
    val label: String,
    val note: String = ""
)

/**
 * 网盘更新链接在线获取器。
 *
 * 从代码仓库的 network_drive_links.json 文件动态获取网盘链接：
 * { "pan_url": "...", "pan_type": "...", "notes": "...", "sd_pan_url": "...", "sd_pan_type": "...", "sd_notes": "..." }
 *
 * ## 架构
 *
 * HTTP 请求和缓存由 [RemoteConfigFetcher] 统一管理：
 * - 两个 Provider（本类与 [SdResourceLinkProvider]）共享同一次 HTTP 请求
 * - 主源使用 Gitee Open API（非 raw 链接），避免被仓库判定为滥用 raw
 * - 支持 ETag 条件请求和请求去重
 *
 * 本类仅负责从完整 JSON 中解析 `pan_*` 前缀字段，并管理自己的 DataStore 离线缓存。
 *
 * ## 缓存策略
 * 1. [RemoteConfigFetcher] 内存缓存（60 秒 TTL）：两个 Provider 共享
 * 2. DataStore 持久化缓存（7 天最大有效期）：App 重启后离线兜底
 *
 * @param forceRefresh true 时跳过内存缓存，且使用主源优先策略（准确性优先）
 */
@Singleton
class NetworkDriveLinkProvider @Inject constructor(
    private val fetcher: RemoteConfigFetcher,
    private val settings: PlayerSettingsDataStore
) {

    companion object {
        private const val TAG = "NetworkDriveLinkProvider"

        /** 持久化缓存最大有效期（毫秒）。超过此时间的缓存视为过期，不作为降级兜底 */
        private const val PERSISTED_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }

    /**
     * 在线获取网盘下载链接。
     *
     * @param forceRefresh true 时跳过缓存，确保拿到最新链接
     * @return 网盘链接；若获取失败且无任何缓存则返回 null
     */
    suspend fun getLink(forceRefresh: Boolean = false): NetworkDriveLink? = withContext(Dispatchers.IO) {
        // 通过共享获取层拿到完整 JSON 配置（两个 Provider 共享一次 HTTP 请求）
        val config = fetcher.fetchConfig(forceRefresh)
        if (config != null) {
            val link = parseConfig(config)
            if (link != null) {
                // 持久化到 DataStore 作为跨重启兜底
                try {
                    settings.setCachedDriveLink(serializeLink(link))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist drive link cache: ${e.message}")
                }
                return@withContext link
            }
        }

        // 在线获取失败：降级到 DataStore 持久化缓存
        try {
            val persisted = settings.getCachedDriveLink()
            if (persisted != null) {
                val age = System.currentTimeMillis() - persisted.second
                if (age > PERSISTED_CACHE_MAX_AGE_MS) {
                    Log.w(TAG, "Persisted cache is too old (age=${age}ms), discarding")
                    settings.clearCachedDriveLink()
                } else {
                    val restored = deserializeLink(persisted.first)
                    if (restored != null) {
                        Log.w(TAG, "Config fetch failed, returning persisted DataStore cache (age=${age}ms, label=${restored.label})")
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
     * 从完整 JSON 配置中解析网盘链接字段（`pan_url` / `pan_type` / `notes`）。
     */
    private fun parseConfig(root: JSONObject): NetworkDriveLink? {
        return try {
            val url = root.optString("pan_url").trim()
            if (!isValidUrl(url)) {
                Log.w(TAG, "Invalid or missing pan_url: $url")
                return null
            }
            val label = root.optString("pan_type").trim().ifEmpty { "网盘" }
            val note = root.optString("notes").trim()
            Log.i(TAG, "Parsed drive link: label=$label, url=$url")
            NetworkDriveLink(url = url, label = label, note = note)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config: ${e.message}", e)
            null
        }
    }

    /** 将 [NetworkDriveLink] 序列化为 JSON 字符串，用于持久化到 DataStore */
    private fun serializeLink(link: NetworkDriveLink): String {
        return JSONObject().apply {
            put("pan_url", link.url)
            put("pan_type", link.label)
            put("notes", link.note)
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
            NetworkDriveLink(url = url, label = label, note = note)
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
