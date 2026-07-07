package com.azurlane.blyy.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用更新检测结果
 *
 * @param versionName 最新版本号（不含 v 前缀）
 * @param changelog   更新日志（来自 GitHub Release body）
 * @param downloadUrl GitHub Releases 直链（APK 资源 URL 或 Release 页面 URL）
 * @param driveLink   网盘备用下载链接（在线获取自 GitHub 仓库）；
 *                    为 null 表示未配置网盘链接，UI 仅显示 GitHub 更新方式
 * @param currentVersion 当前应用版本号（用于 UI 版本对比展示）
 */
data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val driveLink: NetworkDriveLink? = null,
    val currentVersion: String = ""
)

/**
 * 应用启动时自动检测更新的工具类。
 *
 * 职责：
 * 1. 调用 GitHub Releases API 获取最新版本信息
 * 2. 与当前应用版本进行比对
 * 3. 尊重用户"稍后提醒"设置（跳过已忽略的版本）
 * 4. 处理网络异常、API 请求失败等边界情况
 * 5. 记录详细日志便于排查问题
 * 6. 在线获取网盘备用下载链接（不改变版本/日志数据来源）
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PlayerSettingsDataStore,
    private val driveLinkProvider: NetworkDriveLinkProvider
) {
    private val currentVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }

    companion object {
        private const val TAG = "AppUpdateChecker"
        private const val GITHUB_API_LATEST = "https://api.github.com/repos/oneroomlife/blyy/releases/latest"
        private const val GITHUB_RELEASE_PAGE = "https://github.com/oneroomlife/blyy/releases"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 400L

        /** Release 信息缓存有效期（毫秒）。短缓存让连续启动秒级返回，同时保证及时性。 */
        private const val RELEASE_CACHE_TTL_MS = 10 * 60 * 1000L
    }

    /** 缓存的 release 原始三元组 (version, changelog, downloadUrl) 与时间戳 */
    @Volatile
    private var cachedRelease: Triple<String, String, String>? = null
    @Volatile
    private var cachedReleaseAt: Long = 0L

    /**
     * 执行更新检测。
     *
     * 优化点：
     * - 自动检测开关与跳过版本号并行读取 DataStore，省去串行等待
     * - Release 信息带 10 分钟短缓存：连续启动时秒级返回，避免每次都发起网络请求
     * - 版本检测与网盘链接获取并行执行（async），缩短启动等待时长
     * - 若版本检测判定为"无需更新/已跳过"，则丢弃网盘链接结果，避免浪费 UI 资源
     *
     * @param forceRefresh true 时跳过 release 缓存强制重新拉取（用于"关于"页手动检查）
     * @return UpdateInfo 如果有新版本可用且未被用户跳过；null 如果无需更新或检测失败
     */
    suspend fun checkForUpdate(forceRefresh: Boolean = false): UpdateInfo? {
        // 并行读取自动检测开关与跳过版本号，省去串行等待
        val (enabled, skippedVersion) = coroutineScope {
            val enabledDeferred = async { settings.autoCheckUpdateEnabled.first() }
            val skippedDeferred = async { settings.skippedUpdateVersion.first() }
            enabledDeferred.await() to skippedDeferred.await()
        }

        if (!enabled) {
            Log.d(TAG, "Auto update check is disabled by user, skipping")
            return null
        }

        return try {
            coroutineScope {
                // 并行启动：版本检测 + 网盘链接获取
                val releaseDeferred = async { fetchLatestReleaseWithRetry(forceRefresh) }
                val driveLinkDeferred = async {
                    try {
                        driveLinkProvider.getLink()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load drive link: ${e.message}")
                        null
                    }
                }

                val result = releaseDeferred.await()
                if (result == null) {
                    Log.w(TAG, "Failed to fetch latest release info after retries")
                    return@coroutineScope null
                }

                val (latestVersion, changelog, downloadUrl) = result
                Log.i(TAG, "Current: $currentVersion, Latest: $latestVersion")
                Log.d(TAG, "Changelog length: ${changelog.length}, Download URL: $downloadUrl")

                if (!isNewerVersion(latestVersion)) {
                    Log.d(TAG, "App is up to date")
                    return@coroutineScope null
                }

                // 检查用户是否已跳过此版本（skippedVersion 已并行读取）
                if (skippedVersion == latestVersion) {
                    Log.d(TAG, "User skipped version $latestVersion, not showing update dialog")
                    return@coroutineScope null
                }

                // 等待网盘链接结果（通常已先于版本检测完成）
                val driveLink = driveLinkDeferred.await()
                Log.i(TAG, "New version available: $latestVersion (hasDriveLink=${driveLink != null})")
                UpdateInfo(
                    versionName = latestVersion,
                    changelog = changelog.ifEmpty { "暂无更新日志" },
                    downloadUrl = downloadUrl.ifEmpty { GITHUB_RELEASE_PAGE },
                    driveLink = driveLink,
                    currentVersion = currentVersion
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }

    /**
     * 记录用户跳过的版本号
     */
    suspend fun skipVersion(version: String) {
        settings.setSkippedUpdateVersion(version)
        Log.d(TAG, "User skipped version: $version")
    }

    /**
     * 获取当前版本号（供外部查询）
     */
    fun getAppVersion(): String = currentVersion

    private suspend fun fetchLatestReleaseWithRetry(forceRefresh: Boolean): Triple<String, String, String>? {
        // 缓存命中且未过期时直接返回，避免每次启动都发起网络请求
        if (!forceRefresh) {
            val cached = cachedRelease
            if (cached != null && System.currentTimeMillis() - cachedReleaseAt < RELEASE_CACHE_TTL_MS) {
                Log.d(TAG, "Returning cached release info (age=${System.currentTimeMillis() - cachedReleaseAt}ms)")
                return cached
            }
        }

        repeat(MAX_RETRIES) { attempt ->
            val result = fetchLatestRelease()
            if (result != null) {
                // 写入缓存
                cachedRelease = result
                cachedReleaseAt = System.currentTimeMillis()
                return result
            }
            if (attempt < MAX_RETRIES - 1) {
                Log.d(TAG, "Retry ${attempt + 1}/$MAX_RETRIES after ${RETRY_DELAY_MS}ms delay")
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
            }
        }
        // 所有重试失败：若有过期缓存，降级返回过期缓存（保证可用性优先于及时性）
        cachedRelease?.let {
            Log.w(TAG, "All retries failed, returning stale release cache")
            return it
        }
        return null
    }

    private suspend fun fetchLatestRelease(): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Fetching from: $GITHUB_API_LATEST")
                val url = URL(GITHUB_API_LATEST)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "BLYY-Android-App/${currentVersion}")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        Log.d(TAG, "Response length: ${response.length}")
                        parseReleaseResponse(response)
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> {
                        Log.e(TAG, "Release not found (404) - no releases published yet")
                        null
                    }
                    403 -> {
                        Log.e(TAG, "API rate limit exceeded (403)")
                        null
                    }
                    else -> {
                        val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        Log.e(TAG, "HTTP $responseCode: $errorStream")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during update check: ${e.message}", e)
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun parseReleaseResponse(response: String): Triple<String, String, String>? {
        return try {
            val release = JSONObject(response)
            val tagName = release.getString("tag_name").removePrefix("v")
            val body = release.optString("body", "").trim()
            val htmlUrl = release.optString("html_url", GITHUB_RELEASE_PAGE)

            // 格式化 changelog - 限制长度，截断过长内容
            val formattedChangelog = formatChangelog(body)

            val assets = release.optJSONArray("assets")
            var downloadUrl = ""

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.getString("browser_download_url")
                        Log.d(TAG, "Found APK: $name -> $downloadUrl")
                        break
                    }
                }
            }

            if (downloadUrl.isEmpty()) {
                downloadUrl = htmlUrl
                Log.d(TAG, "No APK asset found, using release page: $htmlUrl")
            }

            Log.i(TAG, "Parsed release: version=$tagName, hasChangelog=${body.isNotEmpty()}, hasApk=${downloadUrl != htmlUrl}")
            // 修复：Triple 顺序必须与解构顺序一致 (version, changelog, downloadUrl)
            Triple(tagName, formattedChangelog, downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release response: ${e.message}", e)
            null
        }
    }

    private fun formatChangelog(body: String): String {
        if (body.isEmpty()) return ""

        // 移除 Markdown 标题标记，保留内容
        var formatted = body
            .replace(Regex("^#+\\s*"), "")
            .replace(Regex("\\n#+\\s*"), "\n")
            .trim()

        // 限制长度，避免弹窗过大
        if (formatted.length > 500) {
            formatted = formatted.take(500) + "..."
        }

        return formatted
    }

    private fun isNewerVersion(latestVersion: String): Boolean {
        return try {
            val current = normalizeVersion(currentVersion)
            val latest = normalizeVersion(latestVersion)

            Log.d(TAG, "Version compare: current=$current, latest=$latest")

            for (i in 0 until maxOf(current.size, latest.size)) {
                val currentPart = current.getOrElse(i) { 0 }
                val latestPart = latest.getOrElse(i) { 0 }

                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Version comparison failed", e)
            false
        }
    }

    private fun normalizeVersion(version: String): List<Int> {
        return version
            .replace(Regex("[^0-9.]"), "")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }
}
