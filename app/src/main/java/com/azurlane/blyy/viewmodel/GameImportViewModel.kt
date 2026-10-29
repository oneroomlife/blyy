package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.Live2dSkinNameRegistry
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.util.gameimport.GameAssetKind
import com.azurlane.blyy.util.gameimport.GameAssetLibrary
import com.azurlane.blyy.util.gameimport.GameAssetNaming
import com.azurlane.blyy.util.gameimport.GameBundleEntry
import com.azurlane.blyy.util.gameimport.GameBundleScanner
import com.azurlane.blyy.util.gameimport.GameImportIndex
import com.azurlane.blyy.util.gameimport.ShellState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
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

    /** 取消批量导入（当前文件完成后停止） */
    data object CancelImport : GameImportIntent

    /** 类型筛选（null = 全部） */
    data class SelectKind(val kind: GameAssetKind?) : GameImportIntent

    /** 切换分区 */
    data class SelectTab(val tab: GameImportTab) : GameImportIntent

    /** 删除已导入资源 */
    data class DeleteAsset(val kind: GameAssetKind, val id: String) : GameImportIntent

    /**
     * 批量删除已导入资源（资源库多选删除）。
     *
     * [keys] 为 (类型, 资源 id) 列表：一次 IO 批量删除，
     * 结果用横幅汇总「已删除 N 个 / 失败明细」，不再逐个弹窗。
     */
    data class DeleteAssets(val keys: List<Pair<GameAssetKind, String>>) : GameImportIntent

    /** 重命名已导入资源 */
    data class RenameAsset(val kind: GameAssetKind, val id: String, val newName: String) : GameImportIntent

    /** 关闭提示横幅 */
    data object ConsumeMessage : GameImportIntent

    /**
     * 把已导入的 SD 小人直接设为「今日秘书舰」。
     *
     * 打通「游戏资源导入 → 秘书舰」的最后一步：以前用户要先记住资源名，
     * 再去秘书舰设置里翻 SD 资源库；现在导入完在卡片上点一下即可。
     */
    data class SetAsSecretary(val kind: GameAssetKind, val id: String) : GameImportIntent
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
    private val nameResolver: Live2dNameResolver,
    private val skinNameRegistry: Live2dSkinNameRegistry,
    private val secretaryManager: SecretaryManager
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
        /** 通道应答文本（READY 时是服务端 ping，失败时是原因） */
        val shellDetail: String? = null,
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
        val pinyinMap: Map<String, String> = emptyMap(),
        /**
         * wiki 换装名序列快照（舰名 → [换装2、换装3…] 的真实皮肤名）。
         *
         * 只用于触发重组：显示名由 [nameResolver] 通过注册表钩子实时组合，
         * 这里保存快照是为了让「换装N → 真实皮肤名」的升级能刷新列表。
         */
        val skinNameSequences: Map<String, List<String>> = emptyMap(),
        /**
         * 设备解码能力提示（如「GPU 只支持 ASTC 4x4」）。
         *
         * 过场图与 SD 小人的贴图是 ASTC 6x6，设备不支持时导入必然失败，
         * 提前说明比让用户对着「导入失败」猜要好。
         */
        val deviceWarning: String? = null
    ) {
        val busy: Boolean get() = scanning || progress != null

        /**
         * 是否需要在资源列表顶部展示 Shizuku 提示卡。
         *
         * 只覆盖「没连通」的状态：确认连上（[ShellState.READY]）之后提示就该消失，
         * 页面顶部留给资源内容本身。`UNKNOWN` 只是首帧的瞬态，不算「需要提示」。
         */
        val shellNeedsAttention: Boolean
            get() = shellState == ShellState.NOT_RUNNING ||
                shellState == ShellState.NO_PERMISSION ||
                shellState == ShellState.SERVICE_UNREACHABLE

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

        /** 已导入（含可更新）的条目数 */
        val importedCount: Int
            get() = entries.count {
                it.state == GameBundleEntry.State.IMPORTED || it.state == GameBundleEntry.State.CHANGED
            }

        /** 待导入：从未导入过 + 上次失败。CHANGED 不算（已导入，只是可更新） */
        val pendingCount: Int
            get() = entries.count {
                it.state == GameBundleEntry.State.NEW || it.state == GameBundleEntry.State.FAILED
            }


        /** 可更新（已导入但源包/管线变化） */
        val updatableCount: Int
            get() = entries.count { it.state == GameBundleEntry.State.CHANGED }

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
        viewModelScope.launch {
            refreshShellStateNow()
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scan()
            } else {
                _state.update { it.copy(error = "Shizuku 授权被拒绝，可稍后重新授权") }
            }
        }
    }

    init {
        scanner.addPermissionListener(permissionListener)
        viewModelScope.launch {
            // 舰名反查（拼音 → 中文）就绪后刷新显示名
            nameResolver.pinyinMap.collect { map ->
                _state.update { it.copy(pinyinMap = map) }
                requestSkinNames()
            }
        }
        viewModelScope.launch {
            // 真实换装名就绪后刷新显示名：把「换装N」升级为 wiki 皮肤名
            skinNameRegistry.skinSequences.collect { sequences ->
                _state.update { it.copy(skinNameSequences = sequences) }
            }
        }
        viewModelScope.launch {
            refreshLibrary()
            refreshDeviceCapability()
            refreshShellStateNow()
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
                // 只处理「从未导入」和「上次失败」的条目；
                // CHANGED（已导入但源包/管线更新过）是可选更新，
                // 不纳入「全部导入」，避免重复搬运几十 GB 数据。
                _state.value.filteredEntries.filter {
                    it.state == GameBundleEntry.State.NEW || it.state == GameBundleEntry.State.FAILED
                }
            )
            GameImportIntent.CancelImport -> {
                cancelRequested = true
                _state.update { it.copy(progress = it.progress?.copy(message = "正在停止…")) }
            }
            is GameImportIntent.SelectKind -> _state.update { it.copy(kindFilter = intent.kind) }
            is GameImportIntent.SelectTab -> _state.update { it.copy(tab = intent.tab) }
            is GameImportIntent.DeleteAsset -> deleteAsset(intent.kind, intent.id)
            is GameImportIntent.DeleteAssets -> deleteAssets(intent.keys)
            is GameImportIntent.RenameAsset -> renameAsset(intent.kind, intent.id, intent.newName)
            GameImportIntent.ConsumeMessage -> _state.update {
                it.copy(batchMessage = null, error = null)
            }
            is GameImportIntent.SetAsSecretary -> setAsSecretary(intent.kind, intent.id)
        }
    }

    /**
     * 把导入的 SD 小人设为今日秘书舰。
     *
     * SD 资源库给游戏导入的资源统一加 `game/` 前缀（见 `SDResourceManager`），
     * 这里按同一约定拼 id；同时清空皮肤选择，让秘书舰落到该资源的默认骨骼。
     */
    private fun setAsSecretary(kind: GameAssetKind, id: String) {
        if (kind != GameAssetKind.SD_CHAR) return
        val resourceId = "game/$id"
        secretaryManager.setSdResourceId(resourceId)
        _state.update {
            it.copy(batchMessage = "已设为今日秘书舰：${displayNameFor(kind, id)}（可到「今日秘书舰 → 秘书舰设置」调整皮肤/大小）")
        }
    }

    /** 显示名：拼音 id → 中文舰名 + 换装/变体说明（与查看器共用同一套解析） */
    fun displayNameFor(asset: GameAssetInfo): String = displayNameFor(asset.kind, asset.id)

    fun displayNameFor(kind: GameAssetKind, id: String): String = nameResolver.displayNameFor(id)

    fun displayNameOfEntry(entry: GameBundleEntry): String = nameResolver.displayNameFor(entry.assetId)

    /**
     * 为当前列表里出现的舰名拉取真实换装名（wiki 台词表）。
     *
     * 注册表内部按舰名去重、失败允许重试，重复调用零网络成本；
     * 就绪后经 [Live2dNameResolver.skinNameProvider] 自动把「换装N」升级为真实皮肤名。
     */
    private fun requestSkinNames() {
        val current = _state.value
        val ids = buildSet {
            current.entries.forEach { add(it.assetId) }
            current.assets.values.forEach { list -> list.forEach { add(it.id) } }
        }
        ids.forEach { id ->
            nameResolver.baseShipName(id)?.let(skinNameRegistry::request)
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 探测 GPU 实际支持的 ASTC 块尺寸并生成提示。
     *
     * 屏幕上的「过场图 / SD 小人」都用 ASTC 6x6；模拟器与部分低端 GPU 只实现 4x4。
     * 这类设备现在会自动走 CPU 软件解码（见 `CompressedTextureDecoder.decodeAstcSoftware`），
     * **不再导入失败**，所以这里只提示「会用软件解码、首次稍慢」，不再是错误警告。
     */
    private fun refreshDeviceCapability() {
        viewModelScope.launch(Dispatchers.IO) {
            val blocks = runCatching {
                com.azurlane.blyy.util.unity.CompressedTextureDecoder.supportedAstcBlockSizes()
            }.getOrDefault(emptySet())
            val warning = when {
                6 in blocks -> null
                else -> "当前 GPU 未硬件支持 ASTC 6x6：SD 小人与过场图会自动改用 CPU 解码" +
                    "（画质一致，单张大图解码会慢一些）"
            }
            _state.update { it.copy(deviceWarning = warning) }
        }
    }

    /**
     * 刷新通道状态（含一次真实 Binder 往返）。
     *
     * 会先丢掉缓存代理，等价于用户点「重试」：服务端进程被回收、
     * 或者上次绑定卡死之后，这是唯一能让扫描恢复的动作。
     */
    fun refreshShellState() {
        viewModelScope.launch {
            scanner.resetShell()
            refreshShellStateNow()
        }
    }

    /** 探测通道并写回状态（挂起版，供需要「探测完再决定」的流程调用） */
    private suspend fun refreshShellStateNow() {
        val (shell, detail) = scanner.probeState()
        _state.update {
            it.copy(
                shellState = shell,
                shizukuVersion = scanner.versionLabel(),
                shellDetail = detail
            )
        }
    }

    fun requestPermission() {
        viewModelScope.launch {
            refreshShellStateNow()
            when (_state.value.shellState) {
                ShellState.NOT_RUNNING -> _state.update {
                    it.copy(error = "请先启动 Shizuku 应用（并保持服务运行）")
                }
                ShellState.SERVICE_UNREACHABLE -> {
                    // 已授权但服务不可用：重试绑定比再弹一次授权更有效
                    scanner.resetShell()
                    refreshShellStateNow()
                }
                else -> if (!scanner.requestPermission()) {
                    _state.update { it.copy(error = "无法拉起 Shizuku 授权弹窗") }
                }
            }
        }
    }

    /** 扫描游戏目录（Shizuku 不可用时只刷新本地资源库） */
    fun scan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            refreshShellStateNow()
            val shell = _state.value.shellState
            if (shell != ShellState.READY) {
                refreshLibrary()
                when (shell) {
                    ShellState.NO_PERMISSION -> _state.update {
                        it.copy(error = "授权 Shizuku 后才能读取游戏资源目录")
                    }
                    ShellState.SERVICE_UNREACHABLE -> _state.update {
                        it.copy(
                            error = "Shizuku 已授权但通道不可用：" +
                                (_state.value.shellDetail ?: "服务未响应") + "（可点重试重新连接）"
                        )
                    }
                    ShellState.NOT_RUNNING -> _state.update {
                        it.copy(error = "请先启动 Shizuku（并保持服务运行）后再扫描")
                    }
                    else -> Unit
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
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val reason = t.message ?: "扫描失败：${t.javaClass.simpleName}"
                // 扫描失败多通道导致（绑定超时 / 远端进程被杀）：
                // 同步把通道状态降级，界面才会重新出现「重试」入口，而不是继续显示已连接
                _state.update {
                    it.copy(
                        scanning = false,
                        shellState = ShellState.SERVICE_UNREACHABLE,
                        shellDetail = reason,
                        error = reason
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
            requestSkinNames()
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

    /**
     * 批量导入结果文案。
     *
     * @param success 成功数量
     * @param failed 失败明细（文件名 → 原因）
     * @param cancelled 是否被用户中途停止
     */
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

    /**
     * 批量删除（资源库多选）。
     *
     * 逐个删除并汇总结果：成功数量 + 失败明细首项，最后统一刷新资源库与索引，
     * 避免多选删除时弹出 N 个对话框、也避免列表出现「删了一半」的中间态。
     */
    private fun deleteAssets(keys: List<Pair<GameAssetKind, String>>) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            var deleted = 0
            val failed = ArrayList<String>()
            keys.forEach { (kind, id) ->
                val label = displayNameFor(kind, id)
                if (runCatching { library.delete(kind, id) }.getOrDefault(false)) {
                    deleted++
                    index.removeByAsset(kind, id)
                } else {
                    failed += label
                }
            }
            _state.update {
                it.copy(
                    batchMessage = buildString {
                        append("已删除 $deleted 个资源")
                        if (failed.isNotEmpty()) {
                            append("，${failed.size} 个失败（${failed.first()}）")
                        }
                    }
                )
            }
            refreshLibrary()
        }
    }

    private fun renameAsset(kind: GameAssetKind, id: String, newName: String) {        viewModelScope.launch {
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
