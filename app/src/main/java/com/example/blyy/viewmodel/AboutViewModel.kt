package com.example.blyy.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class AboutState(
    val selectedChannel: UpdateChannel = UpdateChannel.STABLE,
    val isCheckingUpdate: Boolean = false,
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val latestVersion: String = "",
    val downloadUrl: String = ""
)

enum class UpdateChannel(val displayName: String) {
    STABLE("稳定版"),
    BETA("测试版")
}

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class UpdateAvailable(val version: String, val changelog: String, val releaseUrl: String) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

sealed class AboutIntent {
    data class SelectChannel(val channel: UpdateChannel) : AboutIntent()
    object CheckUpdate : AboutIntent()
}

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(AboutState())
    val state: StateFlow<AboutState> = _state.asStateFlow()

    private val currentVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }

    companion object {
        private const val TAG = "AboutViewModel"
        private const val GITHUB_API_BASE = "https://api.github.com/repos/oneroomlife/blyy/releases"
        private const val GITHUB_RELEASE_PAGE = "https://github.com/oneroomlife/blyy/releases"
    }

    fun onIntent(intent: AboutIntent) {
        when (intent) {
            is AboutIntent.SelectChannel -> selectChannel(intent.channel)
            is AboutIntent.CheckUpdate -> checkUpdate()
        }
    }

    private fun selectChannel(channel: UpdateChannel) {
        _state.update { it.copy(selectedChannel = channel, updateStatus = UpdateStatus.Idle) }
    }

    private fun checkUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(isCheckingUpdate = true, updateStatus = UpdateStatus.Checking) }

            try {
                val result = withContext(Dispatchers.IO) {
                    fetchLatestVersion()
                }

                if (result != null) {
                    val latestVersion = result.first
                    val downloadUrl = result.second
                    val changelog = result.third

                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestVersion = latestVersion,
                            downloadUrl = downloadUrl
                        )
                    }

                    if (isNewerVersion(latestVersion)) {
                        _state.update {
                            it.copy(updateStatus = UpdateStatus.UpdateAvailable(
                                latestVersion, 
                                changelog,
                                downloadUrl.ifEmpty { GITHUB_RELEASE_PAGE }
                            ))
                        }
                    } else {
                        _state.update { it.copy(updateStatus = UpdateStatus.UpToDate) }
                    }
                } else {
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateStatus = UpdateStatus.Error("无法获取更新信息，请稍后重试")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                val errorMessage = when {
                    e.message?.contains("timeout", ignoreCase = true) == true -> "连接超时，请检查网络"
                    e.message?.contains("no host", ignoreCase = true) == true -> "网络连接失败"
                    e.message?.contains("UnknownHost", ignoreCase = true) == true -> "无法解析服务器地址"
                    else -> "网络错误: ${e.message}"
                }
                _state.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateStatus = UpdateStatus.Error(errorMessage)
                    )
                }
            }
        }
    }

    private fun fetchLatestVersion(): Triple<String, String, String>? {
        var connection: HttpURLConnection? = null
        return try {
            val channel = _state.value.selectedChannel
            val apiUrl = when (channel) {
                UpdateChannel.STABLE -> "$GITHUB_API_BASE/latest"
                UpdateChannel.BETA -> GITHUB_API_BASE
            }

            Log.d(TAG, "Fetching from: $apiUrl")
            
            val url = URL(apiUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "BLYY-Android-App")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                parseReleaseResponse(response, channel)
            } else {
                Log.e(TAG, "HTTP error: $responseCode")
                val errorStream = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Error response: $errorStream")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch version", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseReleaseResponse(response: String, channel: UpdateChannel): Triple<String, String, String>? {
        return try {
            if (channel == UpdateChannel.BETA) {
                val releases = org.json.JSONArray(response)
                if (releases.length() > 0) {
                    parseReleaseObject(releases.getJSONObject(0))
                } else null
            } else {
                val release = JSONObject(response)
                parseReleaseObject(release)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response", e)
            null
        }
    }

    private fun parseReleaseObject(release: JSONObject): Triple<String, String, String>? {
        return try {
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
                    Log.d(TAG, "Found APK: $name -> $downloadUrl")
                    break
                }
            }
            
            if (downloadUrl.isEmpty()) {
                downloadUrl = htmlUrl
                Log.d(TAG, "No APK found, using release page: $downloadUrl")
            }
            
            Triple(tagName, downloadUrl, body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse release object", e)
            null
        }
    }

    private fun isNewerVersion(latestVersion: String): Boolean {
        return try {
            val current = normalizeVersion(currentVersion)
            val latest = normalizeVersion(latestVersion)
            
            Log.d(TAG, "Comparing versions: current=$currentVersion, latest=$latestVersion")
            
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

    fun getVersionName(): String = currentVersion
}
