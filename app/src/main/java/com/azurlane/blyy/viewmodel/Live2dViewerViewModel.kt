package com.azurlane.blyy.viewmodel

import android.content.Context
import android.webkit.WebViewClient
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dLibrary
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.util.Live2dWebViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live2D 查看器状态：按导航参数 modelId 载入模型元信息，
 * 接收 WebView 渲染端回传的缩略图并持久化。
 */
@HiltViewModel
class Live2dViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: Live2dLibrary
) : ViewModel() {

    val modelId: String = savedStateHandle.get<String>("modelId").orEmpty()

    /** 库界面解析好的中文显示名（导航参数，仅展示用） */
    val modelName: String = savedStateHandle.get<String>("name").orEmpty()

    data class State(
        val loading: Boolean = true,
        val info: Live2dModelInfo? = null,
        /** 模型库层错误（模型不存在/损坏） */
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val info = try {
                library.findById(modelId)
            } catch (e: Exception) {
                null
            }
            _state.update {
                if (info == null) {
                    it.copy(loading = false, info = null, error = "模型不存在或已损坏（缺少 moc3/model3.json）")
                } else {
                    it.copy(loading = false, info = info, error = null)
                }
            }
        }
    }

    /** 渲染端截图回传 → 持久化到 .thumbs/<id>.jpg */
    fun saveThumb(bytes: ByteArray) {
        viewModelScope.launch {
            library.saveThumb(modelId, bytes)
            _state.update { s ->
                s.info?.let { info ->
                    s.copy(info = info.copy(thumbFile = library.thumbFileFor(modelId)))
                } ?: s
            }
        }
    }

    /** 构建 WebView 拦截器（assets 运行时 + 本地模型文件的虚拟域映射） */
    fun createWebViewClient(
        context: Context,
        infoProvider: () -> Live2dModelInfo?
    ): WebViewClient = Live2dWebViewClient(context.applicationContext, library, infoProvider)
}
