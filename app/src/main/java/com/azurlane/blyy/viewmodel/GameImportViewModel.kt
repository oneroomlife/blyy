package com.azurlane.blyy.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.util.gameimport.GameAssetKind
import com.azurlane.blyy.util.gameimport.GameAssetLibrary
import com.azurlane.blyy.util.gameimport.GameAssetNaming
import com.azurlane.blyy.util.gameimport.GameBundleEntry
import com.azurlane.blyy.util.gameimport.GameBundleScanner
import com.azurlane.blyy.util.gameimport.GameImportBatchResult
import com.azurlane.blyy.util.gameimport.GameImportIndex
import com.azurlane.blyy.util.gameimport.ShellState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** 资源导入页的两个分区 */
enum class GameImportTab(val label: String) {
    BUNDLES("资源包"),
    LIBRARY("已导入")
}

/** 游戏资源导入界面意图 */
sealed interface GameImportIntent {
    /** 重新扫描游戏资源目录 */
    data object Scan : GameImportIntent

    /** 刷新 Shizuku 通道状态 */
    data object RefreshShell : GameImportIntent

    /** 拉起 Shizuku 授权 */
    data object RequestPermission : GameImportIntent

    /** 导入单个资源包 */
    data class ImportEntry(val entry: GameBundleEntry) : GameImportIntent

    /** 导入全部未导入/有更新的资源包 */
    data object ImportPending : GameImportIntent

    /** SAF 多选文件导入 */
    data class ImportUris(val uris: List<Uri>) : GameImportIntent

    /** SAF 目录导入（整棵资源目录树） */
    data class ImportTree(val treeUri: Uri) : GameImportIntent

    /** 取消批量导入（当前文件完成后停止） */
    data object CancelImport : GameImportIntent

    /** 类型筛选（null = 全部） */
    data class SelectKind(val kind: GameAssetKind?) : GameImportIntent

    /** 切换分区 */
    data class SelectTab(val tab: GameImportTab) : GameImportIntent

    /** 删除已导入资源 */
    data class DeleteAsset(val kind: GameAssetKind, val id: String) : GameImportIntent

    /** 重命名已导入资源 */
    data class RenameAsset(val kind: GameAssetKind, val id: String, val newName: String) : GameImportIntent

    /** 关闭提示横幅 */
    data object ConsumeMessage : GameImportIntent
}

/**
 * 游戏资源导入的界面状态与业务编排。
 *
 * 与 Live2D 皮肤库完全解耦：这里只负责「游戏资源包 → 可渲染资源」，
 * Live2D 皮肤库仍是它自己的入口，二者共享的是产物目录（Live2D 走原库）。
 */
@HiltViewModel
class GameImportViewModel @Inject constructor(
    private val scanner: GameBundleScanner,
    private val library: GameAssetLibrary,
    private val index: GameImportIndex,
    private val nameResolver: Live2dNameResolver
) : ViewModel() {

    data class ImportProgress(
        val fileName: String,
        val index: Int,
        val total: Int,
        val fraction: Float,
        val message: String
    )

    data class State(
        val shellState: ShellState = ShellState.UNKNOWN,
        val shizukuVersion: String? = null,
        val tab: GameImportTab = GameImportTab.BUNDLES,
        /** null = 全部类型 */
        val kindFilter: GameAssetKind? = null,
        val scanning: Boolean = false,
        val entries: List<GameBundleEntry> = emptyList(),
        val assets: Map<GameAssetKind, List<GameAssetInfo>> = emptyMap(),
        val roots: Map<GameAssetKind, String> = emptyMap(),
        val progress: ImportProgress? = null,
        /** 批量导入结果文案 */
        val batchMessage: String? = null,
        val error: String? = null,
        val lastScanAt: Long = 0L,
        /** 舰名拼音映射（就绪后显示名自动升级为中文） */
        val pinyinMap: Map<String, String> = emptyMap()
    ) {
        val busy: Boolean get() = scanning || progress != null

        val filteredEntries: List<GameBundleEntry>
            get() = kindFilter?.let { kind -> entries.filter { it.kind == kind } } ?: entries

        fun assetsOf(kind: GameAssetKind?): List<GameAssetInfo> =
            if (kind == null) {
                GameAssetKind.ordered.flatMap { assets[it].orEmpty() }
            } else {
                assets[kind].orEmpty()
            }

        val filteredAssets: List<GameAssetInfo>
            get() = assetsOf(kindFilter)

        val importedCount: Int get() = entries.count { it.state == GameBundleEntry.State.IMPORTED }
        val pendingCount: Int get() = entries.count { it.state != GameBundleEntry.State.IMPORTED }
        val failedCount: Int get() = entries.count { it.state == GameBundleEntry.State.FAILED }
        val totalAssets: Int get() = assets.values.sumOf { it.size }

        fun countOf(kind: GameAssetKind?): Int =
            if (kind == null) entries.size else entries.count { it.kind == kind }

        fun importedCountOf(kind: GameAssetKind?): Int =
            if (kind == null) totalAssets else assets[kind].orEmpty().size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var importJob: Job? = null
    private var cancelRequested = false

    private val permissionListener: (Int, Int) -> Unit = { _, grantResult ->
        refreshShellState()
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scan()
        } else {
            _state.update { it.copy(error = "Shizuku 授权被拒绝，可稍后重新授权") }
        }
    }

    init {
        scanner.addPermissionListener(permissionListener)
        viewModelScope.launch {
            // 舰名反查（拼音 → 中文）就绪后刷新显示名
            nameResolver.pinyinMap.collect { map ->
                _state.update { it.copy(pinyinMap = map) }
            }
        }
        viewModelScope.launch {
            refreshShellState()
            refreshLibrary()
            if (_state.value.shellState == ShellState.READY) scan()
        }
    }

    override fun onCleared() {
        scanner.removePermissionListener(permissionListener)
        scanner.release()
        super.onCleared()
    }

    fun onIntent(intent: GameImportIntent) {
        when (intent) {
            GameImportIntent.Scan -> scan()
            GameImportIntent.RefreshShell -> refreshShellState()
            GameImportIntent.RequestPermission -> requestPermission()
            is GameImportIntent.ImportEntry -> importEntries(listOf(intent.entry))
            GameImportIntent.ImportPending -> importEntries(
                _state.value.filteredEntries.filter { it.state != GameBundleEntry.State.IMPORTED }
            )
            is GameImportIntent.ImportUris -> importUris(intent.uris)
            is GameImportIntent.ImportTree -> importTree(intent.treeUri)
            GameImportIntent.CancelImport -> {
                cancelRequested = true
                _state.update { it.copy(progress = it.progress?.copy(message = "正在停止…")) }
            }
            is GameImportIntent.SelectKind -> _state.update { it.copy(kindFilter = intent.kind) }
            is GameImportIntent.SelectTab -> _state.update { it.copy(tab = intent.tab) }
            is GameImportIntent.DeleteAsset -> deleteAsset(intent.kind, intent.id)
            is GameImportIntent.RenameAsset -> renameAsset(intent.kind, intent.id, intent.newName)
            GameImportIntent.ConsumeMessage -> _state.update {
                it.copy(batchMessage = null, error = null)
            }
        }
    }

    /** 显示名：拼音 id → 中文舰名 + 变体说明（换装特效 / 改造 …） */
    fun displayNameFor(asset: GameAssetInfo): String = displayNameFor(asset.kind, asset.id)

    fun displayNameFor(kind: GameAssetKind, id: String): String {
        val variant = GameAssetNaming.variantOf(id)
        val baseId = variant?.first ?: id
        val base = nameResolver.displayNameFor(baseId)
        if (variant == null) return base
        // 已含「· 换装N」时追加变体说明；完全没解析出来时退回 id
        return if (base == baseId) "$baseId · ${variant.second}" else "$base · ${variant.second}"
    }

    fun displayNameOfEntry(entry: GameBundleEntry): String {
        val variant = GameAssetNaming.variantOf(entry.assetId)
        val base = nameResolver.displayNameFor(variant?.first ?: entry.assetId)
        return variant?.let { "$base · ${it.second}" } ?: base
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ 扫描

    fun refreshShellState() {
        val shell = scanner.shellState()
        _state.update { it.copy(shellState = shell, shizukuVersion = scanner.versionLabel()) }
    }

    fun requestPermission() {
        refreshShellState()
        if (_state.value.shellState == ShellState.NOT_RUNNING) {
            _state.update { it.copy(error = "请先启动 Shizuku 应用（并保持服务运行）") }
            return
        }
        if (!scanner.requestPermission()) {
            _state.update { it.copy(error = "无法拉起 Shizuku 授权弹窗") }
        }
    }

    /** 扫描游戏目录（Shizuku 不可用时只刷新本地资源库） */
    fun scan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            refreshShellState()
            val shell = _state.value.shellState
            if (shell != ShellState.READY) {
                refreshLibrary()
                if (shell == ShellState.NO_PERMISSION) {
                    _state.update { it.copy(error = "授权 Shizuku 后才能读取游戏资源目录") }
                }
                return@launch
            }
            _state.update { it.copy(scanning = true, error = null) }
            try {
                val roots = scanner.discoverRoots()
                if (roots.isEmpty()) {
                    _state.update {
                        it.copy(
                            scanning = false,
                            error = "未找到碧蓝航线资源目录（请确认游戏已下载资源，或从本地文件导入）"
                        )
                    }
                    refreshLibrary()
                    return@launch
                }
                roots.forEach { (kind, path) -> index.rememberRoot(kind, path) }
                val entries = scanner.scan(roots)
                _state.update {
                    it.copy(
                        scanning = false,
                        entries = entries,
                        roots = roots,
                        lastScanAt = System.currentTimeMillis()
                    )
                }
                refreshLibrary()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        scanning = false,
                        error = t.message ?: "扫描失败：${t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /** 重新读取本地资源库（导入/删除/重命名后调用） */
    fun refreshLibrary() {
        viewModelScope.launch {
            val assets = library.scanAll()
            _state.update { it.copy(assets = assets) }
        }
    }

    // ------------------------------------------------------------------ 导入

    private fun importEntries(targets: List<GameBundleEntry>) {
        if (targets.isEmpty() || _state.value.progress != null) return
        cancelRequested = false
        importJob = viewModelScope.launch {
            val success = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            targets.forEachIndexed { position, entry ->
                if (cancelRequested) return@launch
                _state.update {
                    it.copy(
                        progress = ImportProgress(
                            fileName = entry.fileName,
                            index = position + 1,
                            total = targets.size,
                            fraction = 0f,
                            message = "准备导入"
                        )
                    )
                }
                try {
                    val outcome = scanner.importEntry(entry) { fraction, message ->
                        _state.update { current ->
                            current.copy(
                                progress = ImportProgress(
                                    fileName = entry.fileName,
                                    index = position + 1,
                                    total = targets.size,
                                    fraction = ((position + fraction) / targets.size).coerceIn(0f, 1f),
                                    message = message
                                )
                            )
                        }
                    }
                    success += entry.fileName
                    // 实际 id 可能与列表里预估的不同（被别的来源占用时会加哈希后缀），
                    // 必须回写到条目上，否则「查看」会打开错的资源
                    markEntry(entry.path, GameBundleEntry.State.IMPORTED, null, outcome.assetId)
                    // 就地补一条资产卡片，避免整库重扫的等待感（随后 refreshLibrary 校准）
                    val imported = libraryAssetOrNull(outcome.kind, outcome.assetId)
                    if (imported != null) {
                        _state.update { current ->
                            val list = current.assets[imported.kind].orEmpty()
                            if (list.any { it.id == imported.id }) {
                                current
                            } else {
                                current.copy(
                                    assets = current.assets +
                                        (imported.kind to (list + imported).sortedBy { it.id })
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    val reason = t.message ?: t.javaClass.simpleName
                    failed += entry.fileName to reason
                    markEntry(entry.path, GameBundleEntry.State.FAILED, reason)
                }
            }
            _state.update {
                it.copy(
                    progress = null,
                    batchMessage = buildBatchMessage(success.size, failed, cancelRequested)
                )
            }
            refreshLibrary()
        }
    }

    private suspend fun libraryAssetOrNull(kind: GameAssetKind, id: String): GameAssetInfo? =
        runCatching { library.find(kind, id) }.getOrNull()

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.progress != null) return
        cancelRequested = false
        importJob = viewModelScope.launch {
            val result = scanner.importUris(
                uris = uris,
                kindHint = _state.value.kindFilter,
                isCancelled = { cancelRequested },
                onProgress = { position, total, fileName, fraction, message ->
                    _state.update {
                        it.copy(
                            progress = ImportProgress(
                                fileName = fileName,
                                index = position,
                                total = total,
                                fraction = ((position - 1 + fraction) / total).coerceIn(0f, 1f),
                                message = message
                            )
                        )
                    }
                }
            )
            finishBatch(result)
        }
    }

    private fun importTree(treeUri: Uri) {
        if (_state.value.progress != null) return
        cancelRequested = false
        importJob = viewModelScope.launch {
            val result = scanner.importTree(
                treeUri = treeUri,
                isCancelled = { cancelRequested },
                onProgress = { position, total, fileName, fraction, message ->
                    _state.update {
                        it.copy(
                            progress = ImportProgress(
                                fileName = fileName,
                                index = position,
                                total = total,
                                fraction = ((position - 1 + fraction) / total).coerceIn(0f, 1f),
                                message = message
                            )
                        )
                    }
                }
            )
            finishBatch(result)
        }
    }

    private fun finishBatch(result: GameImportBatchResult) {
        _state.update { current ->
            current.copy(
                progress = null,
                batchMessage = buildBatchMessage(result.successCount, result.failed, result.cancelled),
                error = result.failed.firstOrNull()?.let { (name, reason) -> "$name：$reason" } ?: current.error,
                // 本地文件/文件夹导入没有「资源包列表」可看，成功后直接切到已导入，避免以为没生效
                tab = if (result.successCount > 0 && current.tab == GameImportTab.BUNDLES &&
                    current.entries.isEmpty()
                ) {
                    GameImportTab.LIBRARY
                } else {
                    current.tab
                }
            )
        }
        refreshLibrary()
        // 导入完成后刷新一次条目状态，避免列表仍显示「待导入」
        if (result.successCount > 0) scan()
    }

    private fun buildBatchMessage(
        success: Int,
        failed: List<Pair<String, String>>,
        cancelled: Boolean
    ): String = when {
        cancelled -> "导入已取消（已完成 $success 个）"
        failed.isEmpty() && success > 0 -> "已导入 $success 个资源"
        failed.isEmpty() -> "没有可导入的资源"
        success == 0 -> "导入失败：${failed.size} 个（${failed.first().second}）"
        else -> "导入完成：成功 $success，失败 ${failed.size}（${failed.first().second}）"
    }

    private fun markEntry(
        path: String,
        state: GameBundleEntry.State,
        error: String?,
        assetId: String? = null
    ) {
        _state.update { current ->
            current.copy(
                entries = current.entries.map {
                    if (it.path == path) {
                        it.copy(state = state, error = error, assetId = assetId ?: it.assetId)
                    } else {
                        it
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------ 资源库操作

    private fun deleteAsset(kind: GameAssetKind, id: String) {
        viewModelScope.launch {
            val label = displayNameFor(kind, id)
            val ok = library.delete(kind, id)
            _state.update {
                it.copy(
                    batchMessage = if (ok) "已删除「$label」" else "删除失败：$label",
                    assets = if (ok) {
                        it.assets.toMutableMap().apply {
                            put(kind, it.assets[kind].orEmpty().filterNot { asset -> asset.id == id })
                        }
                    } else {
                        it.assets
                    }
                )
            }
            index.removeByAsset(kind, id)
            refreshLibrary()
        }
    }

    private fun renameAsset(kind: GameAssetKind, id: String, newName: String) {
        viewModelScope.launch {
            val target = GameAssetNaming.sanitizeId(newName)
            val error = library.rename(kind, id, target)
            if (error == null && target != id) {
                // 索引里的 assetId 必须跟着改，否则下次扫描会把改名后的资源当成新包
                index.renameAsset(kind, id, target)
            }
            _state.update {
                it.copy(batchMessage = error?.let { reason -> "重命名失败：$reason" } ?: "已重命名")
            }
            refreshLibrary()
        }
    }
}
