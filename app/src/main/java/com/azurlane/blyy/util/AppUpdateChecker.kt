package com.azurlane.blyy.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用更新检测结果
 */
data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val downloadUrl: String
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
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PlayerSettingsDataStore
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
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    /**
     * 执行更新检测。
     *
     * @return UpdateInfo 如果有新版本可用且未被用户跳过；null 如果无需更新或检测失败
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        // 先检查用户是否开启了自动检测
        val enabled = settings.autoCheckUpdateEnabled.first()
        if (!enabled) {
            Log.d(TAG, "Auto update check is disabled by user, skipping")
            return null
        }

        return try {
            val result = fetchLatestRelease()
            if (result == null) {
                Log.w(TAG, "Failed to fetch latest release info")
                return null
            }

            val (latestVersion, changelog, downloadUrl) = result
            Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion")

            if (!isNewerVersion(latestVersion)) {
                Log.d(TAG, "App is up to date")
                return null
            }

            // 检查用户是否已跳过此版本
            val skippedVersion = settings.skippedUpdateVersion.first()
            if (skippedVersion == latestVersion) {
                Log.d(TAG, "User skipped version $latestVersion, not showing update dialog")
                return null
            }

            Log.i(TAG, "New version available: $latestVersion")
            UpdateInfo(
                versionName = latestVersion,
                changelog = changelog,
                downloadUrl = downloadUrl.ifEmpty { GITHUB_RELEASE_PAGE }
            )
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

    private suspend fun fetchLatestRelease(): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Fetching from: $GITHUB_API_LATEST")
                val url = URL(GITHUB_API_LATEST)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "BLYY-Android-App")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    parseReleaseResponse(response)
                } else {
                    val errorStream = connection.errorStream?.bufferedReader()?.readText()
                    Log.e(TAG, "HTTP $responseCode: $errorStream")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during update check", e)
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun parseReleaseResponse(response: String): Triple<String, String, String>? {
        return try {
            val release = JSONObject(response)
            val tagName = release.getString("tag_name").removePrefix("v")
            val body = release.optString("body", "暂无更新日志").trim()
            val htmlUrl = release.optString("html_url", GITHUB_RELEASE_PAGE)

            val assets = release.getJSONArray("assets")
            var downloadUrl = ""

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    Log.d(TAG, "Found APK: $name")
                    break
                }
            }

            if (downloadUrl.isEmpty()) {
                downloadUrl = htmlUrl
                Log.d(TAG, "No APK asset found, using release page")
            }

            Triple(tagName, downloadUrl, body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release response", e)
            null
        }
    }

    private fun isNewerVersion(latestVersion: String): Boolean {
        return try {
            val current = normalizeVersion(currentVersion)
            val latest = normalizeVersion(latestVersion)

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
