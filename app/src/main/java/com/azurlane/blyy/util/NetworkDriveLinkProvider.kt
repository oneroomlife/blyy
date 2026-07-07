package com.azurlane.blyy.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网盘下载链接（在线获取，来自 GitHub 仓库 network_drive_links.json）。
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
 * 从 GitHub 仓库 oneroomlife/blyy 的 network_drive_links.json 文件动态获取网盘链接：
 * { "pan_url": "...", "pan_type": "...", "notes": "..." }
 *
 * 设计说明：
 * - 链接配置存储在 GitHub 仓库，开发者可在不发布新版本 APK 的情况下更新链接
 * - 主源使用 raw.githubusercontent.com，备用源使用 jsDelivr CDN（国内访问更稳定）
 * - 版本号、更新日志仍从 GitHub Releases API 获取；本类仅提供"备用下载渠道"
 * - 获取失败时不抛异常，返回 null，由调用方降级处理（仅显示 GitHub 更新方式）
 * - 30 秒内缓存结果，避免短时间内重复网络请求
 */
@Singleton
class NetworkDriveLinkProvider @Inject constructor() {

    companion object {
        private const val TAG = "NetworkDriveLinkProvider"

        /** GitHub Raw 主源 */
        private const val PRIMARY_URL =
            "https://raw.githubusercontent.com/oneroomlife/blyy/main/network_drive_links.json"

        /** jsDelivr CDN 备用源（国内访问更稳定） */
        private const val FALLBACK_URL =
            "https://cdn.jsdelivr.net/gh/oneroomlife/blyy@main/network_drive_links.json"

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000

        /** 缓存有效期（毫秒），避免短时间内重复请求 */
        private const val CACHE_TTL_MS = 30_000L
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
     * 策略（并行竞速优化）：
     * 1. 若缓存有效（30 秒内），直接返回缓存
     * 2. 否则同时发起主源（raw.githubusercontent.com）和备用源（jsDelivr）请求，
     *    谁先成功就用谁的结果（select 竞速），另一请求自动取消
     * 3. 若两个源都失败，返回过期缓存（若有）或 null
     *
     * 性能：旧实现串行尝试，主源不可达时需等待 13s 超时后才尝试备用源；
     *      新实现并行竞速，最快路径仅需 jsDelivr 单次往返（国内通常 <1s）。
     *
     * @return 网盘链接；若获取失败且无缓存则返回 null
     */
    suspend fun getLink(): NetworkDriveLink? = withContext(Dispatchers.IO) {
        // 检查缓存是否有效
        val now = System.currentTimeMillis()
        if (cachedLink != null && now - cachedAt < CACHE_TTL_MS) {
            Log.d(TAG, "Returning cached drive link (age=${now - cachedAt}ms)")
            return@withContext cachedLink
        }

        // 并行竞速：同时发起主源和备用源请求，谁先成功用谁
        val winner = coroutineScope {
            val primaryDeferred = async { fetchAndParse(PRIMARY_URL) }
            val fallbackDeferred = async { fetchAndParse(FALLBACK_URL) }

            // select 返回第一个完成的结果（可能为 null 表示该源失败）
            val firstResult = select {
                primaryDeferred.onAwait { it }
                fallbackDeferred.onAwait { it }
            }

            if (firstResult != null) {
                Log.i(TAG, "Race winner returned a valid link")
                return@coroutineScope firstResult
            }

            // 第一个完成的是失败（null），等待另一个源完成
            Log.d(TAG, "First source failed, waiting for the other")
            primaryDeferred.await() ?: fallbackDeferred.await()
        }

        if (winner != null) {
            cachedLink = winner
            cachedAt = System.currentTimeMillis()
            return@withContext winner
        }

        // 所有源都失败，返回过期缓存（若有）
        if (cachedLink != null) {
            Log.w(TAG, "All sources failed, returning stale cache")
            return@withContext cachedLink
        }

        Log.w(TAG, "All sources failed and no cache available")
        null
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
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $responseCode from $urlString")
                return null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            parseJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch from $urlString: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析 JSON 配置为 [NetworkDriveLink]。
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
            Log.i(TAG, "Parsed drive link: label=$label, url=$url")
            NetworkDriveLink(url = url, label = label, note = note)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
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
