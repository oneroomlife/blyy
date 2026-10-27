package com.azurlane.blyy.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dImporter
import com.azurlane.blyy.util.Live2dLibrary
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.Live2dSelection
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

    /** 批量删除（多选模式下确认执行） */
    data class DeleteSelected(val ids: Set<String>) : Live2dIntent()

    /** 进入多选模式（可预置选中项） */
    data class StartSelecting(val initial: Set<String> = emptySet()) : Live2dIntent()

    /** 退出多选模式并清空选中 */
    object StopSelecting : Live2dIntent()

    /** 切换单个模型的选中态 */
    data class ToggleSelected(val id: String) : Live2dIntent()

    /** 全选/全不选当前列表 */
    data class SelectAll(val ids: Set<String>, val selected: Boolean) : Live2dIntent()

    /** 重命名模型（改名目录） */
    data class Rename(val id: String, val newName: String) : Live2dIntent()

    /** 消费导入结果横幅（关闭提示） */
    object ConsumeImportResult : Live2dIntent()

    /** 消费批量删除结果横幅（关闭提示） */
    object ConsumeDeleteResult : Live2dIntent()
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
    private val skinNameRegistry: Live2dSkinNameRegistry
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val models: List<Live2dModelInfo> = emptyList(),
        /** 扫描到的无效目录（缺少 model3.json 等），为空时隐藏提示 */
        val invalidDirs: List<String> = emptyList(),
        /** 扫描失败（IO 错误等），非空且模型为空时展示错误态 */
        val scanError: String? = null,
        val importProgress: Live2dImporter.ImportProgress? = null,
        val importResult: Live2dImporter.ImportResult? = null,
        /** 批量删除结果横幅文案（成功/部分失败），null = 不展示 */
        val deleteResultText: String? = null,
        /** 是否处于多选模式（工具栏切换、卡片显示勾选圈） */
        val selecting: Boolean = false,
        /** 多选模式下已选中的模型 id */
        val selectedIds: Set<String> = emptySet()
    ) {
        /** 选中项的磁盘占用合计（多选工具条展示） */
        val selectedBytes: Long
            get() = models.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var importJob: Job? = null

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
            is Live2dIntent.StartSelecting -> _state.update {
                it.copy(selecting = true, selectedIds = intent.initial)
            }
            is Live2dIntent.StopSelecting -> _state.update {
                it.copy(selecting = false, selectedIds = emptySet())
            }
            is Live2dIntent.ToggleSelected -> _state.update { current ->
                current.copy(selectedIds = Live2dSelection.toggle(current.selectedIds, intent.id))
            }
            is Live2dIntent.SelectAll -> _state.update { current ->
                current.copy(
                    selectedIds = Live2dSelection.selectAll(
                        current.selectedIds, intent.ids, intent.selected
                    )
                )
            }
            is Live2dIntent.DeleteSelected -> deleteSelected(intent.ids)
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
            is Live2dIntent.ConsumeDeleteResult -> _state.update { it.copy(deleteResultText = null) }
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

    /**
     * 批量删除：一次 IO 完成，结果复用 [State.importResult] 横幅通道汇报
     * （成功数量 / 释放空间 / 失败明细），随后重扫并退出多选模式。
     */
    private fun deleteSelected(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val result = library.deleteAll(ids)
            _state.update { current ->
                // 成功信息走独立文案（neutral/成功样式），失败明细追加在后面；
                // 不再借用 ImportResult.failed 通道——那会被渲染成"警告/失败"语义
                val text = buildString {
                    if (result.deleted > 0) {
                        append("已删除 ${result.deleted} 个模型，释放 ${formatBytes(result.freedBytes)}")
                    }
                    if (result.failed.isNotEmpty()) {
                        if (isNotEmpty()) append("；")
                        append("${result.failed.size} 个删除失败：")
                        append(result.failed.joinToString("、") { (id, reason) -> "$id（$reason）" })
                    }
                }.ifBlank { null }
                current.copy(
                    selecting = false,
                    selectedIds = emptySet(),
                    deleteResultText = text
                )
            }
            refresh()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, scanError = null) }
            try {
                val (models, invalid) = library.scan()
                _state.update { current ->
                    val alive = models.mapTo(HashSet()) { it.id }
                    current.copy(
                        loading = false,
                        models = models,
                        invalidDirs = invalid.map { d -> "${d.id}（${d.reason}）" },
                        scanError = null,
                        // 模型被删掉后把失效的选中项一并清掉，避免工具条显示虚高数量
                        selectedIds = Live2dSelection.retainExisting(current.selectedIds, alive),
                        selecting = current.selecting && models.isNotEmpty()
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
