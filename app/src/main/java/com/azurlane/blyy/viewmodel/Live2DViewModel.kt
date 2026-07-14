package com.azurlane.blyy.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Live2D 加载阶段详情 */
data class Live2DLoadState(
    val phase: Live2DLoadPhase = Live2DLoadPhase.Loading,
    val progress: Int = 0,
    val errorMessage: String = "",
    val sslErrorCode: Int = -1,
    val sslErrorMessage: String = "",
    val loadStartTimeMs: Long = 0L,
    val pageStartedTimeMs: Long = 0L,
    val pageVisibleTimeMs: Long = 0L,
    val pageFinishedTimeMs: Long = 0L,
    val errorTimeMs: Long = 0L,
    val consoleErrors: List<String> = emptyList(),
    val webGLStatus: String = "unknown",
    val reloadToken: Int = 0,
    val webViewGeneration: Int = 0,
    val isRecreateRequired: Boolean = false,
    val requestLogs: List<Live2DRequestLogEntry> = emptyList()
)

enum class Live2DLoadPhase { Loading, Ready, Error, SslWarning }

/**
 * Live2D 请求生命周期日志条目。
 * 用于结构化记录每一次请求/回调的关键事件，确保错误排查的可追溯性。
 *
 * @param timestamp 事件发生的时间戳（System.currentTimeMillis）
 * @param phase 事件阶段标识，如 "pre-flight" / "page-started" / "http-error" / "retry"
 * @param message 人类可读的事件描述
 * @param details 结构化键值对细节（如 url / statusCode / elapsedMs / cookies）
 */
data class Live2DRequestLogEntry(
    val timestamp: Long,
    val phase: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

@HiltViewModel
class Live2DViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    val sslTrusted: StateFlow<Boolean> = settings.live2dSslTrusted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _loadState = MutableStateFlow(Live2DLoadState())
    val loadState: StateFlow<Live2DLoadState> = _loadState.asStateFlow()

    fun setSslTrusted(trusted: Boolean) {
        viewModelScope.launch { settings.setLive2dSslTrusted(trusted) }
    }

    fun updateLoadState(update: (Live2DLoadState) -> Live2DLoadState) {
        _loadState.value = update(_loadState.value)
    }

    fun triggerReload(recreate: Boolean = false) {
        _loadState.value = _loadState.value.copy(
            reloadToken = _loadState.value.reloadToken + 1,
            webViewGeneration = if (recreate) _loadState.value.webViewGeneration + 1 else _loadState.value.webViewGeneration,
            isRecreateRequired = false,
            phase = Live2DLoadPhase.Loading,
            progress = 6 // INITIAL_PROGRESS
        )
    }

    fun markRecreateRequired() {
        _loadState.value = _loadState.value.copy(isRecreateRequired = true)
    }

    fun addConsoleError(error: String) {
        _loadState.value = _loadState.value.copy(
            consoleErrors = _loadState.value.consoleErrors + error
        )
    }

    /**
     * 追加一条请求生命周期日志，用于错误排查的可追溯性。
     * 日志会在 [generateErrorReport] 中输出，方便用户复制反馈。
     */
    fun addRequestLog(phase: String, message: String, details: Map<String, String> = emptyMap()) {
        val entry = Live2DRequestLogEntry(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            message = message,
            details = details
        )
        // 限制日志条数，避免无限增长
        val current = _loadState.value.requestLogs
        val updated = if (current.size >= 60) current.takeLast(59) + entry else current + entry
        _loadState.value = _loadState.value.copy(requestLogs = updated)
    }

    /** 生成完整的错误诊断报告，便于复制给技术人员分析 */
    fun generateErrorReport(context: Context): String {
        val state = _loadState.value
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
        val now = Date()

        val sb = StringBuilder()
        sb.appendLine("=== Live2D 错误诊断报告 ===")
        sb.appendLine("生成时间: ${dateFormat.format(now)}")
        sb.appendLine()

        // 设备信息
        sb.appendLine("--- 设备信息 ---")
        sb.appendLine("设备型号: ${Build.MODEL}")
        sb.appendLine("设备厂商: ${Build.MANUFACTURER}")
        sb.appendLine("Android 版本: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("设备时间: ${dateFormat.format(now)}")
        sb.appendLine("WebView UA: Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
        sb.appendLine()

        // 网络信息
        sb.appendLine("--- 网络信息 ---")
        sb.appendLine("网络类型: ${getNetworkType(context)}")
        sb.appendLine()

        // 加载状态
        sb.appendLine("--- 加载状态 ---")
        sb.appendLine("当前阶段: ${state.phase}")
        sb.appendLine("加载进度: ${state.progress}%")
        sb.appendLine("加载开始: ${if (state.loadStartTimeMs > 0) dateFormat.format(Date(state.loadStartTimeMs)) else "未开始"}")
        sb.appendLine("页面开始加载: ${if (state.pageStartedTimeMs > 0) dateFormat.format(Date(state.pageStartedTimeMs)) else "未到达"}")
        sb.appendLine("页面可见: ${if (state.pageVisibleTimeMs > 0) dateFormat.format(Date(state.pageVisibleTimeMs)) else "未到达"}")
        sb.appendLine("页面完成: ${if (state.pageFinishedTimeMs > 0) dateFormat.format(Date(state.pageFinishedTimeMs)) else "未到达"}")
        if (state.loadStartTimeMs > 0 && state.pageFinishedTimeMs > 0) {
            sb.appendLine("总加载耗时: ${state.pageFinishedTimeMs - state.loadStartTimeMs}ms")
        }
        sb.appendLine("WebGL 状态: ${state.webGLStatus}")
        sb.appendLine()

        // 错误信息
        if (state.errorMessage.isNotEmpty() || state.sslErrorMessage.isNotEmpty()) {
            sb.appendLine("--- 错误信息 ---")
            if (state.sslErrorMessage.isNotEmpty()) {
                sb.appendLine("SSL 错误: ${state.sslErrorMessage}")
                sb.appendLine("SSL 错误码: ${state.sslErrorCode}")
                sb.appendLine("SSL 错误时间: ${if (state.errorTimeMs > 0) dateFormat.format(Date(state.errorTimeMs)) else "N/A"}")
            }
            if (state.errorMessage.isNotEmpty()) {
                sb.appendLine("错误消息: ${state.errorMessage}")
                sb.appendLine("错误时间: ${if (state.errorTimeMs > 0) dateFormat.format(Date(state.errorTimeMs)) else "N/A"}")
            }
            sb.appendLine()
        }

        // 控制台错误
        if (state.consoleErrors.isNotEmpty()) {
            sb.appendLine("--- 控制台错误 ---")
            state.consoleErrors.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        // 请求生命周期日志（可追溯性核心）
        if (state.requestLogs.isNotEmpty()) {
            sb.appendLine("--- 请求生命周期日志 ---")
            state.requestLogs.forEach { entry ->
                sb.appendLine("[${dateFormat.format(Date(entry.timestamp))}] ${entry.phase}: ${entry.message}")
                entry.details.forEach { (k, v) -> sb.appendLine("    $k=$v") }
            }
            sb.appendLine()
        }

        // SSL 信任状态
        sb.appendLine("--- SSL 信任状态 ---")
        sb.appendLine("已信任 l2d.su: ${sslTrusted.value}")
        sb.appendLine()

        // 设备时间偏差检查
        val timeDiff = System.currentTimeMillis() - now.time
        if (kotlin.math.abs(timeDiff) > 5000) {
            sb.appendLine("--- ⚠️ 时间偏差警告 ---")
            sb.appendLine("检测到设备时间可能不准确，偏差约 ${timeDiff}ms")
            sb.appendLine("不准确的系统时间可能导致 SSL 证书验证失败")
            sb.appendLine()
        }

        sb.appendLine("=== 报告结束 ===")
        return sb.toString()
    }

    private fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "未知"
        val network = cm.activeNetwork ?: return "无网络连接"
        val caps = cm.getNetworkCapabilities(network) ?: return "未知"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
    }
}
