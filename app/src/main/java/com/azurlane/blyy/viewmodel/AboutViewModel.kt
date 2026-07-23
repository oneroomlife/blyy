package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.AppUpdateChecker
import com.azurlane.blyy.util.ManualCheckResult
import com.azurlane.blyy.util.ManualCheckStatus
import com.azurlane.blyy.util.NetworkDriveLink
import com.azurlane.blyy.util.UpdateChannel
import com.azurlane.blyy.util.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AboutState(
    val selectedChannel: UpdateChannel = UpdateChannel.STABLE,
    val isCheckingUpdate: Boolean = false,
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val latestVersion: String = "",
    val downloadUrl: String = "",
    val driveLink: NetworkDriveLink? = null
)

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

/**
 * "关于"页 ViewModel。
 *
 * 重构说明（复用 AppUpdateChecker，消除重复实现）：
 * - 移除了独立的 fetchLatestVersion / parseReleaseResponse / isNewerVersion 等重复逻辑
 * - 改为调用 [AppUpdateChecker.forceCheckUpdate]，与启动检查共享同一套核心逻辑
 * - 网盘链接获取使用多源版本择优策略（forceRefresh=true），确保拿到最新链接
 * - 支持稳定版/测试版渠道切换
 *
 * 优势：
 * - 消除约 150 行重复代码（版本解析、URL 构建、HTTP 请求、版本比较）
 * - 启动检查与手动检查行为一致，避免两套实现产生差异
 * - 集中维护网络请求逻辑，便于后续优化
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateChecker: AppUpdateChecker
) : ViewModel() {

    private val _state = MutableStateFlow(AboutState())
    val state: StateFlow<AboutState> = _state.asStateFlow()

    companion object {
        private const val TAG = "AboutViewModel"
    }

    fun onIntent(intent: AboutIntent) {
        when (intent) {
            is AboutIntent.SelectChannel -> selectChannel(intent.channel)
            is AboutIntent.CheckUpdate -> checkUpdate()
        }
    }

    private fun selectChannel(channel: UpdateChannel) {
        _state.update {
            it.copy(
                selectedChannel = channel,
                updateStatus = UpdateStatus.Idle,
                driveLink = null,
                downloadUrl = "",
                latestVersion = ""
            )
        }
    }

    private fun checkUpdate() {
        viewModelScope.launch {
            _state.update { it.copy(isCheckingUpdate = true, updateStatus = UpdateStatus.Checking) }

            try {
                val channel = _state.value.selectedChannel
                Log.d(TAG, "Manual update check started, channel=$channel")

                // 调用 AppUpdateChecker 统一的手动检查接口
                // 内部会：强制刷新 release 缓存 + 多源版本择优获取网盘链接
                val result: ManualCheckResult = updateChecker.forceCheckUpdate(channel)

                when (result.status) {
                    ManualCheckStatus.UPDATE_AVAILABLE -> {
                        val info: UpdateInfo = result.updateInfo!!
                        _state.update {
                            it.copy(
                                isCheckingUpdate = false,
                                latestVersion = info.versionName,
                                downloadUrl = info.downloadUrl,
                                driveLink = info.driveLink,
                                updateStatus = UpdateStatus.UpdateAvailable(
                                    info.versionName,
                                    info.changelog,
                                    info.downloadUrl
                                )
                            )
                        }
                        Log.i(TAG, "Update available: v${info.versionName} (driveLink=${info.driveLink != null})")
                    }
                    ManualCheckStatus.UP_TO_DATE -> {
                        // 即使已是最新，也保留网盘链接供用户下载当前版本
                        val driveLink = try {
                            updateChecker.refreshDriveLink()
                        } catch (e: Exception) {
                            null
                        }
                        _state.update {
                            it.copy(
                                isCheckingUpdate = false,
                                updateStatus = UpdateStatus.UpToDate,
                                driveLink = driveLink
                            )
                        }
                        Log.i(TAG, "App is up to date")
                    }
                    ManualCheckStatus.ERROR -> {
                        _state.update {
                            it.copy(
                                isCheckingUpdate = false,
                                updateStatus = UpdateStatus.Error(result.errorMessage ?: "未知错误"),
                                driveLink = null,
                                downloadUrl = ""
                            )
                        }
                        Log.w(TAG, "Update check failed: ${result.errorMessage}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                _state.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateStatus = UpdateStatus.Error("网络错误: ${e.message}"),
                        driveLink = null,
                        downloadUrl = ""
                    )
                }
            }
        }
    }

    fun getVersionName(): String = updateChecker.getAppVersion()
}
