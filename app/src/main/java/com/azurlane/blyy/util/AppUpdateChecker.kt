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
 * 手动检查更新结果（"关于"页使用）。
 *
 * 比 [UpdateInfo] 多了"已是最新版本"状态，因为手动检查需要明确告知用户当前状态，
 * 而启动自动检查仅在发现新版本时才弹窗。
 *
 * @param status    检查结果状态
 * @param updateInfo 当 status 为 [ManualCheckStatus.UPDATE_AVAILABLE] 时的更新信息
 * @param errorMessage 当 status 为 [ManualCheckStatus.ERROR] 时的错误信息
 */
data class ManualCheckResult(
    val status: ManualCheckStatus,
    val updateInfo: UpdateInfo? = null,
    val errorMessage: String? = null
)

enum class ManualCheckStatus {
    /** 发现新版本 */
    UPDATE_AVAILABLE,
    /** 当前已是最新版本 */
    UP_TO_DATE,
    /** 检查失败 */
    ERROR
}

/**
 * 更新渠道。
 * - STABLE：稳定版，使用 /releases/latest 接口
 * - BETA：测试版，使用 /releases 列表取第一个（通常是预发布版本）
 */
enum class UpdateChannel(val displayName: String) {
    STABLE("稳定版"),
    BETA("测试版")
}

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
        private const val GITHUB_API_LIST = "https://api.github.com/repos/oneroomlife/blyy/releases"
        private const val GITHUB_RELEASE_PAGE = "https://github.com/oneroomlife/blyy/releases"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 400L

        /** Release 信息缓存有效期（毫秒）。短缓存让连续启动秒级返回，同时保证及时性。
         *  从 10 分钟缩短到 3 分钟：GitHub 上发布新版本后，App 在 3 分钟内即可感知到，
         *  避免"GitHub 已发新版本但 App 长期提示已是最新"的问题。 */
        private const val RELEASE_CACHE_TTL_MS = 3 * 60 * 1000L

        /** 过期 Release 缓存最大有效期（毫秒）。所有重试失败降级返回过期缓存时，
         *  超过此时间的缓存视为不可靠，不返回（避免用户看到很久以前的版本信息）。 */
        private const val STALE_RELEASE_MAX_AGE_MS = 60 * 60 * 1000L
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

    /**
     * 强制刷新网盘链接（绕过内存缓存）。
     *
     * 供 UI 在用户点击"通过网盘更新"时调用，确保打开的是 GitHub 仓库中最新的网盘链接，
     * 而非启动检查时缓存的旧链接。即使启动后过了一段时间，也能拿到开发者最新配置的网盘地址。
     *
     * 内部使用多源版本择优策略：三源并发，取 version 最大的结果，绝不返回旧链接。
     *
     * @return 最新的网盘链接；获取失败时降级返回缓存（内存/持久化），全部不可用则返回 null
     */
    suspend fun refreshDriveLink(): NetworkDriveLink? {
        return try {
            driveLinkProvider.getLink(forceRefresh = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh drive link: ${e.message}")
            null
        }
    }

    /**
     * 手动检查更新（"关于"页使用）。
     *
     * 与 [checkForUpdate] 的区别：
     * - 忽略"自动检测开关"和"跳过版本"设置（用户主动检查，应给出明确结果）
     * - 强制刷新 release 缓存和网盘链接缓存（确保拿到最新信息）
     * - 返回 [ManualCheckResult]，包含"已是最新版本"状态（启动检查不区分此状态）
     * - 支持选择更新渠道（STABLE / BETA）
     *
     * 网盘链接使用多源版本择优策略（forceRefresh=true），确保拿到最新链接。
     *
     * @param channel 更新渠道，默认 STABLE
     * @return 检查结果（UPDATE_AVAILABLE / UP_TO_DATE / ERROR）
     */
    suspend fun forceCheckUpdate(channel: UpdateChannel = UpdateChannel.STABLE): ManualCheckResult {
        return try {
            coroutineScope {
                // 并行：release 信息（强制刷新）+ 网盘链接（强制刷新，多源版本择优）
                val releaseDeferred = async { fetchLatestReleaseWithRetry(forceRefresh = true, channel = channel) }
                val driveLinkDeferred = async {
                    try {
                        driveLinkProvider.getLink(forceRefresh = true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load drive link during manual check: ${e.message}")
                        null
                    }
                }

                val result = releaseDeferred.await()
                if (result == null) {
                    Log.w(TAG, "Manual check: failed to fetch release info")
                    return@coroutineScope ManualCheckResult(
                        ManualCheckStatus.ERROR,
                        errorMessage = "无法获取更新信息，请检查网络后重试"
                    )
                }

                val (latestVersion, changelog, downloadUrl) = result
                Log.i(TAG, "Manual check: Current=$currentVersion, Latest=$latestVersion, channel=$channel")

                val driveLink = driveLinkDeferred.await()

                if (isNewerVersion(latestVersion)) {
                    Log.i(TAG, "Manual check: update available v$latestVersion (hasDriveLink=${driveLink != null})")
                    ManualCheckResult(
                        ManualCheckStatus.UPDATE_AVAILABLE,
                        updateInfo = UpdateInfo(
                            versionName = latestVersion,
                            changelog = changelog.ifEmpty { "暂无更新日志" },
                            downloadUrl = downloadUrl.ifEmpty { GITHUB_RELEASE_PAGE },
                            driveLink = driveLink,
                            currentVersion = currentVersion
                        )
                    )
                } else {
                    Log.i(TAG, "Manual check: app is up to date (v$latestVersion)")
                    ManualCheckResult(ManualCheckStatus.UP_TO_DATE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual check failed", e)
            val errorMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> "连接超时，请检查网络"
                e.message?.contains("no host", ignoreCase = true) == true -> "网络连接失败"
                e.message?.contains("UnknownHost", ignoreCase = true) == true -> "无法解析服务器地址"
                else -> "网络错误: ${e.message}"
            }
            ManualCheckResult(ManualCheckStatus.ERROR, errorMessage = errorMessage)
        }
    }

    private suspend fun fetchLatestReleaseWithRetry(
        forceRefresh: Boolean,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): Triple<String, String, String>? {
        // 缓存命中且未过期时直接返回，避免每次启动都发起网络请求
        // 注意：仅 STABLE 渠道使用缓存，BETA 渠道每次都强制请求（避免缓存影响）
        if (!forceRefresh && channel == UpdateChannel.STABLE) {
            val cached = cachedRelease
            if (cached != null && System.currentTimeMillis() - cachedReleaseAt < RELEASE_CACHE_TTL_MS) {
                Log.d(TAG, "Returning cached release info (age=${System.currentTimeMillis() - cachedReleaseAt}ms)")
                return cached
            }
        }

        repeat(MAX_RETRIES) { attempt ->
            val result = fetchLatestRelease(channel)
            if (result != null) {
                // 仅缓存 STABLE 渠道结果
                if (channel == UpdateChannel.STABLE) {
                    cachedRelease = result
                    cachedReleaseAt = System.currentTimeMillis()
                }
                return result
            }
            if (attempt < MAX_RETRIES - 1) {
                Log.d(TAG, "Retry ${attempt + 1}/$MAX_RETRIES after ${RETRY_DELAY_MS}ms delay")
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
            }
        }
        // 所有重试失败：若有过期缓存且未超过最大有效期，降级返回（保证可用性优先于及时性）
        // 仅 STABLE 渠道可降级到缓存
        if (channel == UpdateChannel.STABLE) {
            cachedRelease?.let {
                val age = System.currentTimeMillis() - cachedReleaseAt
                if (age <= STALE_RELEASE_MAX_AGE_MS) {
                    Log.w(TAG, "All retries failed, returning stale release cache (age=${age}ms)")
                    return it
                } else {
                    Log.w(TAG, "Stale release cache is too old (age=${age}ms > max=$STALE_RELEASE_MAX_AGE_MS), discarding")
                }
            }
        }
        return null
    }

    private suspend fun fetchLatestRelease(channel: UpdateChannel = UpdateChannel.STABLE): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = if (channel == UpdateChannel.BETA) GITHUB_API_LIST else GITHUB_API_LATEST
                Log.d(TAG, "Fetching from: $apiUrl (channel=$channel)")
                val url = URL(apiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "BLYY-Android-App/${currentVersion}")
                // 反代理缓存：阻止中间层（运营商/CDN）缓存 API 响应，保证拿到最新 Release
                // 注意：GitHub API 本身支持 ETag，但加 no-cache 不影响 ETag 协商，仅阻止中间代理直接返回缓存
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        Log.d(TAG, "Response length: ${response.length}")
                        parseReleaseResponse(response, channel)
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

    private fun parseReleaseResponse(
        response: String,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): Triple<String, String, String>? {
        return try {
            val release = if (channel == UpdateChannel.BETA) {
                // BETA 渠道：/releases 返回数组，取第一个（通常是最新的预发布版本）
                val releases = org.json.JSONArray(response)
                if (releases.length() > 0) releases.getJSONObject(0) else return null
            } else {
                JSONObject(response)
            }
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

            Log.i(TAG, "Parsed release: version=$tagName, channel=$channel, hasChangelog=${body.isNotEmpty()}, hasApk=${downloadUrl != htmlUrl}")
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
