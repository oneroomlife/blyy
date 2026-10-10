package com.azurlane.blyy.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dImporter
import com.azurlane.blyy.util.Live2dLibrary
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.Live2dResourceLink
import com.azurlane.blyy.util.Live2dResourceLinkProvider
import com.azurlane.blyy.util.Live2dSkinNameRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live2D 模型库界面意图 */
sealed class Live2dIntent {
    /** 手动刷新（重扫根目录） */
    object Refresh : Live2dIntent()

    /** 从 SAF 目录树导入 */
    data class ImportFolder(val uri: Uri) : Live2dIntent()

    /** 从 zip 压缩包导入 */
    data class ImportZip(val uri: Uri) : Live2dIntent()

    /** 取消正在进行的导入 */
    object CancelImport : Live2dIntent()

    /** 删除模型 */
    data class Delete(val id: String) : Live2dIntent()

    /** 重命名模型（改名目录） */
    data class Rename(val id: String, val newName: String) : Live2dIntent()

    /** 消费导入结果横幅（关闭提示） */
    object ConsumeImportResult : Live2dIntent()
}

/**
 * Live2D 皮肤库状态与业务编排。
 *
 * 显示名策略见 [Live2dNameResolver]：拼音目录经舰船数据库反查还原中文舰名。
 */
@HiltViewModel
class Live2dViewModel @Inject constructor(
    private val library: Live2dLibrary,
    private val importer: Live2dImporter,
    private val nameResolver: Live2dNameResolver,
    private val skinNameRegistry: Live2dSkinNameRegistry,
    private val live2dResourceLinkProvider: Live2dResourceLinkProvider
) : ViewModel() {

    companion object {
        private const val TAG = "Live2dViewModel"
    }

    data class State(
        val loading: Boolean = true,
        val models: List<Live2dModelInfo> = emptyList(),
        /** 扫描到的无效目录（缺少 model3.json 等），为空时隐藏提示 */
        val invalidDirs: List<String> = emptyList(),
        /** 扫描失败（IO 错误等），非空且模型为空时展示错误态 */
        val scanError: String? = null,
        val importProgress: Live2dImporter.ImportProgress? = null,
        val importResult: Live2dImporter.ImportResult? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var importJob: Job? = null

    // ── Live2D 资源下载链接状态（远程获取）──

    /** Live2D 模型资源下载链接（在线获取，来自仓库 network_drive_links.json 的 live2d_* 字段） */
    private val _live2dResourceLink = MutableStateFlow<Live2dResourceLink?>(null)
    val live2dResourceLink: StateFlow<Live2dResourceLink?> = _live2dResourceLink.asStateFlow()

    /** Live2D 资源链接加载中标记，供 UI 显示 loading 状态 */
    private val _isLoadingLive2dLink = MutableStateFlow(false)
    val isLoadingLive2dLink: StateFlow<Boolean> = _isLoadingLive2dLink.asStateFlow()

    /**
     * 确保 Live2D 资源下载链接已加载（供页面进入时调用）。
     *
     * 缓存友好的智能重试：启动阶段获取失败（网络异常/仓库文件缺失）时，
     * 进入页面自动重试；已加载成功或正在加载中则跳过，避免重复请求。
     */
    fun ensureLive2dResourceLinkLoaded() {
        if (_live2dResourceLink.value != null || _isLoadingLive2dLink.value) return
        loadLive2dResourceLink(forceRefresh = false)
    }

    private fun loadLive2dResourceLink(forceRefresh: Boolean) {
        viewModelScope.launch {
            _isLoadingLive2dLink.value = true
            try {
                _live2dResourceLink.value = live2dResourceLinkProvider.getLink(forceRefresh = forceRefresh)
                Log.i(TAG, "Live2D resource link loaded: ${_live2dResourceLink.value?.label}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Live2D resource link", e)
            } finally {
                _isLoadingLive2dLink.value = false
            }
        }
    }

    init {
        refresh()
        // 舰名反查就绪后为所有模型请求真实皮肤名（幂等去重；反查未就绪时不发无效请求）
        viewModelScope.launch {
            combine(nameResolver.pinyinMap, skinNameRegistry.skinSequences) { _, _ -> }
                .collect {
                    _state.value.models.forEach { model ->
                        nameResolver.baseShipName(model.id)?.let { skinNameRegistry.request(it) }
                    }
                }
        }
    }

    fun onIntent(intent: Live2dIntent) {
        when (intent) {
            is Live2dIntent.Refresh -> refresh()
            is Live2dIntent.ImportFolder -> startImport { onProgress, isCancelled ->
                importer.importFromTree(intent.uri, onProgress, isCancelled)
            }
            is Live2dIntent.ImportZip -> startImport { onProgress, isCancelled ->
                importer.importFromZip(intent.uri, onProgress, isCancelled)
            }
            is Live2dIntent.CancelImport -> importJob?.cancel()
            is Live2dIntent.Delete -> viewModelScope.launch {
                library.delete(intent.id)
                refresh()
            }
            is Live2dIntent.Rename -> viewModelScope.launch {
                val error = library.rename(intent.id, intent.newName)
                if (error != null) {
                    // 失败信息复用结果横幅通道，避免额外状态
                    _state.update {
                        it.copy(
                            importResult = Live2dImporter.ImportResult(
                                emptyList(), emptyList(), listOf("重命名失败: $error"), false
                            )
                        )
                    }
                }
                refresh()
            }
            is Live2dIntent.ConsumeImportResult -> _state.update { it.copy(importResult = null) }
        }
    }

    /**
     * 模型显示名（StateFlow）：舰名反查就绪或皮肤名注册表更新时自动重算，
     * 「换装N」升级为真实皮肤名（如 aierdeliqi_4 → 埃尔德里奇 · 正月的牵手）。
     */
    val displayNames: StateFlow<Map<String, String>> = combine(
        _state,
        skinNameRegistry.skinSequences,
        nameResolver.pinyinMap
    ) { s, _, _ -> s.models.associate { it.id to displayNameFor(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * 模型显示名：拼音反查中文舰名 + 皮肤名注册表把「换装N」替换为真实皮肤名。
     * 注意：舰名反查就绪前会回退拼音原文，调用方需订阅 [displayNames] 获得升级。
     */
    fun displayNameFor(id: String): String {
        val skinIndex = id.substringAfterLast('_').toIntOrNull()
        val base = nameResolver.baseShipName(id)
            ?: (if (skinIndex != null) id.substringBeforeLast('_') else id)
        val realSkin = skinIndex?.takeIf { it >= 2 }?.let { skinNameRegistry.skinNameFor(base, it) }
        return when {
            skinIndex == null || skinIndex < 2 -> base
            realSkin != null -> "$base · $realSkin"
            else -> "$base · 换装$skinIndex"
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, scanError = null) }
            try {
                val (models, invalid) = library.scan()
                _state.update {
                    it.copy(
                        loading = false,
                        models = models,
                        invalidDirs = invalid.map { d -> "${d.id}（${d.reason}）" },
                        scanError = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, models = emptyList(), scanError = e.message ?: "扫描失败")
                }
            }
        }
    }

    private fun startImport(
        block: suspend (progress: (Live2dImporter.ImportProgress) -> Unit, isCancelled: () -> Boolean) -> Live2dImporter.ImportResult
    ) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            _state.update {
                it.copy(importProgress = Live2dImporter.ImportProgress(Live2dImporter.Phase.SCANNING))
            }
            val result = block(
                { progress -> _state.update { it.copy(importProgress = progress) } },
                { !job.isActive }
            )
            _state.update { it.copy(importProgress = null, importResult = result) }
            if (result.successCount > 0) refresh()
        }
    }
}
