package com.azurlane.blyy.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.WatermarkAssets
import com.azurlane.blyy.util.WatermarkComposer
import com.azurlane.blyy.util.WatermarkEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 水印相机页 ViewModel。
 *
 * 职责边界：
 * - 持有水印列表与相机页选中状态（拍照后传给编辑页做默认水印）
 * - 拍照临时文件的创建与过期清理
 * - CameraX 绑定/拍照等生命周期敏感操作留在 Composable 层（bindToLifecycle 归 LifecycleOwner 管理），
 *   ViewModel 仅提供状态与文件句柄，避免持有相机对象导致泄漏
 */
@HiltViewModel
class WatermarkCameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class CameraUiState(
        /** 全部可用水印（动态发现自 assets/photo_frame） */
        val watermarks: List<WatermarkEntry> = emptyList(),
        /** 当前选中的水印 id；null = 无水印 */
        val selectedWatermarkId: String? = null,
        /** 拍照进行中（防连点 + UI 反馈） */
        val isCapturing: Boolean = false,
        /** 设备是否具备任意相机（无相机时引导走图库流程） */
        val cameraAvailable: Boolean = true,
        /** 相机可用性检测是否完成（避免误闪引导页） */
        val cameraChecked: Boolean = false,
        /** 一次性用户提示（拍照失败等），消费后置 null */
        val message: String? = null
    )

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    init {
        // 与项目惯例一致：init 内所有协程安全启动，异常不外泄
        viewModelScope.launch {
            try {
                WatermarkComposer.cleanStaleCaptures(context)
            } catch (_: Exception) {
                // 清理失败不影响功能
            }
        }
        viewModelScope.launch {
            try {
                val entries = WatermarkAssets.load(context)
                val hasCamera = context.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                _state.update {
                    it.copy(
                        watermarks = entries,
                        cameraAvailable = hasCamera,
                        cameraChecked = true
                    )
                }
            } catch (e: Exception) {
                // 水印列表加载失败时相机仍可用，编辑页会再次尝试加载
                _state.update { it.copy(cameraChecked = true) }
            }
        }
    }

    /** 当前选中的水印条目 */
    fun selectedEntry(): WatermarkEntry? =
        WatermarkAssets.find(_state.value.watermarks, _state.value.selectedWatermarkId)

    /** 创建拍照临时文件 */
    fun createCaptureFile() = WatermarkComposer.createCaptureFile(context)

    fun selectWatermark(id: String?) {
        _state.update { it.copy(selectedWatermarkId = id) }
    }

    fun setCapturing(capturing: Boolean) {
        _state.update { it.copy(isCapturing = capturing) }
    }

    fun showMessage(message: String?) {
        _state.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 拍照失败回调：清理半成品文件并提示 */
    fun onCaptureError(file: java.io.File?, message: String) {
        file?.let { f -> runCatching { if (f.exists()) f.delete() } }
        _state.update { it.copy(isCapturing = false, message = message) }
    }

    /** 拍照成功：进入编辑页（uri = 临时文件） */
    fun onCaptureSuccess() {
        _state.update { it.copy(isCapturing = false) }
    }
}
