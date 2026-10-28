package com.azurlane.blyy.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.Live2dSkinNameRegistry
import com.azurlane.blyy.util.MediaDownloader
import com.azurlane.blyy.util.gameimport.GameAssetExporter
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.util.gameimport.GameAssetKind
import com.azurlane.blyy.util.gameimport.GameAssetLibrary
import com.azurlane.blyy.util.gameimport.GameAssetNaming
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 游戏资源查看器（Spine 小人 / 动态皮肤 / 静态立绘）。
 *
 * 只负责「读一个资源目录 + 展示名」；交互（拖动/缩放/复位）在渲染组件内部完成，
 * 查看器本身不再暴露动作列表 / 倍速拉条等控制项。
 * Live2D 资源仍由 [Live2dViewerViewModel] 渲染，本 VM 不处理。
 */
@HiltViewModel
class GameAssetViewerViewModel @Inject constructor(
    private val library: GameAssetLibrary,
    private val exporter: GameAssetExporter,
    private val nameResolver: Live2dNameResolver,
    private val skinNameRegistry: Live2dSkinNameRegistry,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val kind: GameAssetKind =
        GameAssetKind.fromKey(savedStateHandle.get<String>("kind")) ?: GameAssetKind.SD_CHAR

    private val assetId: String = savedStateHandle.get<String>("assetId").orEmpty()

    data class State(
        val kind: GameAssetKind,
        val loading: Boolean = true,
        val asset: GameAssetInfo? = null,
        val error: String? = null,
        /** wiki 换装名序列快照：仅用于在真实皮肤名就绪后触发重组 */
        val skinNameSequences: Map<String, List<String>> = emptyMap(),
        /** 正在导出（按钮转圈 + 防重复点击） */
        val exporting: Boolean = false,
        /** 一次性提示（导出结果），由界面 Toast 后消费 */
        val toast: String? = null
    ) {
        /** Spine 主骨骼名（查看器只播放默认骨骼） */
        val skeleton: String? get() = asset?.primarySkeleton
    }

    private val _state = MutableStateFlow(State(kind = kind))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val asset = runCatching { library.find(kind, assetId) }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    asset = asset,
                    error = if (asset == null) "资源不存在或已被删除" else null
                )
            }
            // 真实换装名：先按舰名请求，就绪后经注册表钩子自动升级显示名
            nameResolver.baseShipName(assetId)?.let(skinNameRegistry::request)
        }
        viewModelScope.launch {
            skinNameRegistry.skinSequences.collect { sequences ->
                _state.update { it.copy(skinNameSequences = sequences) }
            }
        }
    }

    /**
     * 显示名：中文舰名 + 皮肤/变体说明。
     *
     * 统一交给 [Live2dNameResolver.displayNameFor]——它内部已经处理了
     * 「换装序号（`_4`）」「形态后缀（`_hx` / `_doa` / `_rw` / `_n`）」
     * 与「纯字母数字舰名（`2b` / `a2`）」的大小写，避免查看器与资源库
     * 各写一套导致同一资源在两个页面显示成不同名字。
     */
    fun displayName(asset: GameAssetInfo): String = nameResolver.displayNameFor(asset.id)

    /**
     * 导出当前图片型资源到 `Downloads/BLYY/...`。
     *
     * 文件名 = `<显示名>_<资源 id>.png`：显示名方便人找，id 保证不同皮肤不重名。
     */
    fun export(asset: GameAssetInfo) {
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val result = runCatching { exporter.export(asset, displayName(asset)) }
                .getOrElse { MediaDownloader.Result.Failure(it.message ?: "导出失败") }
            val message = when (result) {
                is MediaDownloader.Result.Success ->
                    "已保存到 Downloads/${result.relativePath}/${result.displayName}"
                is MediaDownloader.Result.Failure -> "导出失败：${result.message}"
            }
            _state.update { it.copy(exporting = false, toast = message) }
        }
    }

    /** 界面已展示完提示 */
    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }
}
