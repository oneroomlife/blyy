package com.azurlane.blyy.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.SaveResult
import com.azurlane.blyy.util.WatermarkAssets
import com.azurlane.blyy.util.WatermarkComposer
import com.azurlane.blyy.util.WatermarkEntry
import com.azurlane.blyy.util.WatermarkParams
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 水印编辑页 ViewModel。
 *
 * 核心状态流：原图 → 解码为单一 Bitmap（[EditorUiState.sourceBitmap]）→（实时预览：
 * Compose 直接渲染该位图 + 参数）→ 保存时 [WatermarkComposer.compose] 复用同一
 * 位图合成（Dispatchers.Default）→ MediaStore 写入（Dispatchers.IO）。
 * 预览与合成共用 [WatermarkComposer.computePlacement] 布局算法与同一解码数据源，
 * 保证所见即所得。
 */
@HiltViewModel
class WatermarkEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class EditorUiState(
        /** 原图（拍照临时文件或图库 content uri） */
        val sourceUri: Uri? = null,
        /**
         * 已解码的原图位图 —— **预览与合成的单一数据源**。
         * 预览直接渲染它，保存时 [WatermarkComposer.compose] 复用同一对象，
         * 从根上消除 Coil 与合成解码管线的差异（EXIF/降采样/色彩），保证所见即所得。
         * 由本 ViewModel 持有，换图时被新位图替换，最终交由 GC 回收。
         */
        val sourceBitmap: Bitmap? = null,
        /** 原图尺寸（已应用 EXIF 方向，来自 [sourceBitmap]），用于预览布局计算 */
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0,
        /** 原图加载中（bounds 解码） */
        val isLoading: Boolean = false,
        /** 原图读取失败（损坏/格式异常/权限丢失） */
        val sourceError: Boolean = false,
        /** 全部可用水印 */
        val watermarks: List<WatermarkEntry> = emptyList(),
        /** 当前水印 id；null = 无水印 */
        val selectedWatermarkId: String? = null,
        /** 水印摆放参数（与预览/合成共用） */
        val params: WatermarkParams = WatermarkParams.Default,
        /** 保存进行中（防连点） */
        val isSaving: Boolean = false,
        /** 一次性保存结果，UI 消费后调用 [consumeSaveResult] 清空 */
        val saveResult: SaveResult? = null
    ) {
        val selectedEntry: WatermarkEntry?
            get() = WatermarkAssets.find(watermarks, selectedWatermarkId)
        val hasSource: Boolean get() = sourceUri != null && sourceWidth > 0
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 让慢速的旧图片复制/解码结果不能覆盖用户后来选择的图片。 */
    private var sourceLoadGeneration = 0

    init {
        viewModelScope.launch {
            try {
                val entries = WatermarkAssets.load(context)
                _state.update { s -> s.copy(watermarks = entries) }
                // 注意：不校验/清空已选 id — selectedEntry 查不到时自然降级为无水印，
                // initialize() 稍后可能携带路由传入的合法 id
            } catch (_: Exception) {
                // 列表失败不阻断编辑，仅无水印可选
            }
        }
    }

    /**
     * 页面进入时初始化（仅一次；重复调用不覆盖已有状态，
     * 防止编辑过程中配置变更/导航重放导致状态被重置）。
     */
    fun initialize(imageUri: Uri?, initialWatermarkId: String?) {
        if (_state.value.sourceUri != null || imageUri == null) return
        val generation = ++sourceLoadGeneration
        _state.update { it.copy(isLoading = true, sourceError = false, selectedWatermarkId = initialWatermarkId) }
        viewModelScope.launch {
            try {
                // 先把外部图库 URI 固化到本应用缓存。不能让编辑流程依赖 Photo Picker
                // 短暂的读取授权，否则导航/配置变化后会变成“图片已失效”。
                val localUri = WatermarkComposer.materializeSourceForEditing(context, imageUri)
                    ?: run {
                        if (generation == sourceLoadGeneration) {
                            _state.update { it.copy(isLoading = false, sourceError = true) }
                        }
                        return@launch
                    }
                // 解码为单一数据源：预览与合成共用同一 Bitmap，像素级所见即所得
                val bitmap = WatermarkComposer.decodeSourceBitmap(context, localUri)
                if (bitmap == null) {
                    WatermarkComposer.deleteIfTempCaptureFile(context, localUri)
                    if (generation == sourceLoadGeneration) {
                        _state.update { it.copy(isLoading = false, sourceError = true) }
                    }
                    return@launch
                }
                if (generation == sourceLoadGeneration) {
                    _state.update {
                        it.copy(
                            sourceUri = localUri,
                            sourceBitmap = bitmap,
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height,
                            isLoading = false
                        )
                    }
                } else {
                    // 用户已换图/离开：本次解码的位图从未展示，直接回收
                    bitmap.recycle()
                    WatermarkComposer.deleteIfTempCaptureFile(context, localUri)
                }
            } catch (e: Exception) {
                if (generation == sourceLoadGeneration) {
                    _state.update { it.copy(isLoading = false, sourceError = true) }
                }
            }
        }
    }

    /** 更换原图（编辑页"重新选图"）：释放旧临时文件 + 重置参数 */
    fun replaceSource(uri: Uri) {
        val generation = ++sourceLoadGeneration
        val old = _state.value
        if (old.sourceUri != null) {
            WatermarkComposer.deleteIfTempCaptureFile(context, old.sourceUri)
        }
        // 先清空状态引用再让旧位图随 GC 回收（不在本帧同步 recycle：
        // Compose 可能仍持有一帧旧 ImageBitmap，recycle 后再绘制会崩溃）
        _state.update {
            it.copy(
                sourceUri = null, sourceBitmap = null, sourceWidth = 0, sourceHeight = 0,
                isLoading = true, sourceError = false,
                params = WatermarkParams.Default // 换图重置参数，避免旧偏移错位
            )
        }
        viewModelScope.launch {
            try {
                val localUri = WatermarkComposer.materializeSourceForEditing(context, uri)
                    ?: run {
                        if (generation == sourceLoadGeneration) {
                            _state.update { it.copy(isLoading = false, sourceError = true) }
                        }
                        return@launch
                    }
                val bitmap = WatermarkComposer.decodeSourceBitmap(context, localUri)
                if (bitmap == null) {
                    WatermarkComposer.deleteIfTempCaptureFile(context, localUri)
                    if (generation == sourceLoadGeneration) {
                        _state.update { it.copy(isLoading = false, sourceError = true) }
                    }
                    return@launch
                }
                if (generation == sourceLoadGeneration) {
                    _state.update {
                        it.copy(
                            sourceUri = localUri,
                            sourceBitmap = bitmap,
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height,
                            isLoading = false
                        )
                    }
                } else {
                    // 用户又换图/离开：本次解码的位图从未展示，直接回收
                    bitmap.recycle()
                    WatermarkComposer.deleteIfTempCaptureFile(context, localUri)
                }
            } catch (e: Exception) {
                if (generation == sourceLoadGeneration) {
                    _state.update { it.copy(isLoading = false, sourceError = true) }
                }
            }
        }
    }

    fun selectWatermark(id: String?) {
        _state.update { it.copy(selectedWatermarkId = id) }
    }

    /**
     * 手势变换结果（双指缩放 + 单指拖动）统一入口。
     *
     * 预览侧已按 [WatermarkComposer.clampPhotoCenter] 的物理边界约束偏移
     * （照片与画布至少相切/交集，拖动范围最大化）；此处仅做兜底：
     * - scale 限制在 [WatermarkParams.SCALE_MIN, WatermarkParams.SCALE_MAX]，
     *   区间足够宽 → 不会出现"放大到一定程度后无法继续放大"
     * - 归一化偏移用 [WatermarkComposer.OFFSET_SANITY_LIMIT] 防异常数据
     */
    fun applyGestureTransform(scale: Float, offsetX: Float, offsetY: Float) {
        val clampedScale = scale.coerceIn(WatermarkParams.SCALE_MIN, WatermarkParams.SCALE_MAX)
        _state.update {
            it.copy(
                params = it.params.copy(
                    scale = clampedScale,
                    offsetX = offsetX.coerceIn(
                        -WatermarkComposer.OFFSET_SANITY_LIMIT,
                        WatermarkComposer.OFFSET_SANITY_LIMIT
                    ),
                    offsetY = offsetY.coerceIn(
                        -WatermarkComposer.OFFSET_SANITY_LIMIT,
                        WatermarkComposer.OFFSET_SANITY_LIMIT
                    )
                )
            )
        }
    }

    /**
     * 合成并保存到系统图库。
     * Android 9 及以下缺少写权限时返回 [SaveResult.NeedPermission]，
     * UI 层请求权限授权成功后直接再次调用本方法即可。
     */
    fun save() {
        val current = _state.value
        val uri = current.sourceUri
        if (uri == null || current.isSaving || current.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                val entry = current.selectedEntry
                val bitmap = WatermarkComposer.compose(context, uri, entry, current.params, current.sourceBitmap)
                if (bitmap == null) {
                    _state.update { it.copy(isSaving = false, saveResult = SaveResult.Error("图片读取失败，请重新选择")) }
                    return@launch
                }
                try {
                    val result = WatermarkComposer.saveToGallery(context, bitmap)
                    _state.update { it.copy(isSaving = false, saveResult = result) }
                } finally {
                    // compose 可能返回预览共享的 sourceBitmap（无水印/降级场景），
                    // 该位图由 ViewModel 持有并持续驱动预览，不能在这里回收
                    if (bitmap !== current.sourceBitmap) bitmap.recycle()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, saveResult = SaveResult.Error("保存失败：${e.message ?: "未知错误"}") ) }
            }
        }
    }

    fun consumeSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }

    // 不在 onCleared() 中删除编辑源文件。
    //
    // Photo Picker 关闭时，部分 ROM 会触发 Activity/NavHost 的短暂重建；此时旧
    // ViewModel 虽然会被清除，但新编辑页仍需要同一张图片。此前在这里立即删除
    // cache/watermark_camera 下的文件，会让新实例拿到一个已删除的 file:// Uri，
    // 从而稳定复现“图片读取失败或已失效”。
    //
    // 这些缓存会在相机页初始化时由 cleanStaleCaptures() 于一小时后清理，且缓存目录
    // 本身也会在系统存储紧张时自动回收；优先保证编辑流程在生命周期波动时可恢复。
}
