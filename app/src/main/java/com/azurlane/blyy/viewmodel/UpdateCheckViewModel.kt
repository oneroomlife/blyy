package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.AppUpdateChecker
import com.azurlane.blyy.util.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 启动更新检测 ViewModel。
 *
 * 职责：
 * - 在 ViewModel 初始化时立即触发一次更新检测（无延迟，确保弹窗及时弹出）
 * - 持有检测结果 [updateInfo]，确保配置变更（如旋转屏幕）时状态不丢失
 * - 提供 [dismissUpdate] 供 UI 在用户操作后清除弹窗状态
 *
 * 设计说明：
 * - [AppUpdateChecker] 是 @Singleton，内部已处理用户跳过版本、自动检测开关等逻辑
 * - ViewModel 的 init 块在 Activity 重建时不会重复执行（仅当进程被杀重建时才会），
 *   避免了 LaunchedEffect 在 recomposition 时可能重复触发的问题
 * - 网络请求在 IO 线程执行，不阻塞 UI 线程，对启动性能无影响
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    val updateChecker: AppUpdateChecker
) : ViewModel() {

    companion object {
        private const val TAG = "UpdateCheckViewModel"
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    init {
        checkForUpdateOnStartup()
    }

    /**
     * 启动时自动检测更新。
     * 立即在 IO 线程执行网络请求，结果写入 [_updateInfo] 供 UI 观察。
     * 不再使用启动延迟：网络请求本身在后台线程，不影响 UI 渲染；移除延迟后弹窗可更早出现。
     */
    private fun checkForUpdateOnStartup() {
        viewModelScope.launch {
            Log.d(TAG, "Starting startup update check immediately")
            val result = updateChecker.checkForUpdate()
            if (result != null) {
                Log.i(TAG, "Update available: v${result.versionName}")
            } else {
                Log.d(TAG, "No update available or check skipped")
            }
            _updateInfo.value = result
        }
    }

    /**
     * 清除当前显示的更新弹窗。
     * 供 UI 在用户选择更新方式或点击"稍后提醒"后调用。
     */
    fun dismissUpdate() {
        _updateInfo.value = null
    }
}
