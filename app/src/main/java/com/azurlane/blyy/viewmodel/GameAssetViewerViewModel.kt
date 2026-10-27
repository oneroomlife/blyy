package com.azurlane.blyy.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dNameResolver
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
 * 只负责「读一个资源目录 + 运行期交互状态」；Live2D 资源仍由原有
 * [Live2dViewerViewModel] 渲染，本 VM 不处理。
 */
@HiltViewModel
class GameAssetViewerViewModel @Inject constructor(
    private val library: GameAssetLibrary,
    private val nameResolver: Live2dNameResolver,
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
        /** 当前播放的骨骼主名（多骨骼资源可切换） */
        val skeleton: String? = null,
        /** 缩放倍率（0.3..2.0） */
        val scale: Float = 1f,
        /** 运行期取到的动画名列表（Spine 加载后回调） */
        val animations: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(State(kind = kind))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val asset = runCatching { library.find(kind, assetId) }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    asset = asset,
                    skeleton = asset?.primarySkeleton,
                    error = if (asset == null) "资源不存在或已被删除" else null
                )
            }
        }
    }

    fun selectSkeleton(name: String) {
        _state.update { current ->
            if (current.skeleton == name || name !in (current.asset?.skeletons.orEmpty())) current
            else current.copy(skeleton = name, animations = emptyList())
        }
    }

    fun setScale(scale: Float) {
        _state.update { it.copy(scale = scale.coerceIn(0.3f, 2.0f)) }
    }

    fun onAnimationsLoaded(names: List<String>) {
        _state.update { it.copy(animations = names) }
    }

    /** 显示名：中文舰名 + 变体说明（UI 在读到 asset 后调用） */
    fun displayName(asset: GameAssetInfo): String {
        val variant = GameAssetNaming.variantOf(asset.id)
        val base = nameResolver.displayNameFor(variant?.first ?: asset.id)
        return variant?.let { "$base · ${it.second}" } ?: base
    }
}
