package com.azurlane.blyy.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.util.Live2dImporter
import com.azurlane.blyy.util.Live2dLibrary
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.util.PinyinHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 显示名策略：模型目录普遍为拼音命名（aierdeliqi_4），扫描后用
 * 舰船数据库（Room）做拼音反查还原中文舰名（如"埃尔德里奇 · 换装4"）；
 * 查不到时保留原目录名，绝不猜测造假。
 */
@HiltViewModel
class Live2dViewModel @Inject constructor(
    private val library: Live2dLibrary,
    private val importer: Live2dImporter,
    shipDao: ShipDao
) : ViewModel() {

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

    /** 拼音 → 中文舰名（来自 Room 舰船库，随数据库刷新自动重建） */
    private var pinyinToName: Map<String, String> = emptyMap()

    private var importJob: Job? = null

    init {
        // 舰船库变化时重建反查表（扫描结果在重组时通过 displayNameFor 即时生效）
        viewModelScope.launch {
            shipDao.getAllShips().collect { ships ->
                pinyinToName = ships.associate { PinyinHelper.toPinyin(it.name) to it.name }
            }
        }
        refresh()
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
     * 模型显示名：`aierdeliqi_4` → "埃尔德里奇 · 换装4"。
     * 先精确匹配全拼，再限定前缀长度做前缀匹配（覆盖"阿达尔伯特亲王"→adaerbote
     * 这类"目录只取舰名前半"的情况），都查不到时保留原目录名。
     */
    fun displayNameFor(id: String): String {
        val skinIndex = id.substringAfterLast('_').toIntOrNull()
        val base = if (skinIndex != null) id.substringBeforeLast('_') else id
        val pinyin = base.lowercase()
        val chinese = pinyinToName[pinyin]
            ?: pinyinToName.entries
                .filter { it.key.startsWith(pinyin) && pinyin.length >= 4 }
                .minByOrNull { it.key.length }
                ?.value
        val name = chinese ?: base
        return if (skinIndex != null && skinIndex >= 2) "$name · 换装$skinIndex" else name
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
