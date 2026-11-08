package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.Live2dSkinNameRegistry
import com.azurlane.blyy.util.gameimport.GameAssetInfo
import com.azurlane.blyy.util.gameimport.GameAssetKind
import com.azurlane.blyy.util.gameimport.GameAssetLibrary
import com.azurlane.blyy.util.gameimport.GameAssetNaming
import com.azurlane.blyy.util.gameimport.GameAssetSortKey
import com.azurlane.blyy.util.gameimport.GameAssetSorting
import com.azurlane.blyy.util.gameimport.GameBundleEntry
import com.azurlane.blyy.util.gameimport.GameBundleScanner
import com.azurlane.blyy.util.gameimport.GameImportIndex
import com.azurlane.blyy.util.gameimport.GameSortOrder
import com.azurlane.blyy.util.gameimport.ShellState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * 设置列表排序（名称 / 时间 × 升序 / 降序）。
     *
     * 两个分区共用一份方案；方案会落盘（DataStore），下次进页面仍是同一顺序。
     * 具体「点芯片 / 点方向按钮」如何推导出新方案由 UI 层用
     * [GameSortOrder.selecting] / [GameSortOrder.toggled] 算，ViewModel 只认结果。
     */
    data class SetSortOrder(val order: GameSortOrder) : GameImportIntent

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
    private val secretaryManager: SecretaryManager,
    private val settings: PlayerSettingsDataStore
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
        /**
         * 列表排序方案（名称 / 时间 × 升序 / 降序），「资源包」与「已导入」共用。
         *
         * 排序结果由 ViewModel 在后台算好写进 [sortedEntries] / [sortedAssets]，
         * 这里只保存「方案」：这样排序选择能跨 Tab、跨页面存活，也能落盘。
         */
        val sortOrder: GameSortOrder = GameSortOrder.Default,
        val scanning: Boolean = false,
        /** 扫描进行中的实时文案（已发现的候选数），完成或失败后清空 */
        val scanStatus: String? = null,
        val entries: List<GameBundleEntry> = emptyList(),
        val assets: Map<GameAssetKind, List<GameAssetInfo>> = emptyMap(),
        /**
         * 已按 [sortOrder] 排好序的资源包（含全部类型；类型/搜索筛选在 UI 侧做）。
         *
         * 排序不在组合期做：名字反查与 ICU 排序键的生成都在后台任务里，
         * UI 只读这一份结果（见 [GameAssetSorting] 的两段式 API）。
         */
        val sortedEntries: List<GameBundleEntry> = emptyList(),
        /** 资源包显示名快照（path → 名称）：卡片标题与搜索共用，避免逐条反查舰名 */
        val entryNames: Map<String, String> = emptyMap(),
        /** 已按 [sortOrder] 排好序的已导入资源（含全部类型） */
        val sortedAssets: List<GameAssetInfo> = emptyList(),
        /** 资源显示名快照（`kind/id` → 名称） */
        val assetNames: Map<String, String> = emptyMap(),
        /**
         * 排序结果版本，每发布一批 [sortedEntries] / [sortedAssets] 就 +1。
         *
         * UI 用它当 `remember` 键：直接拿列表当键会退化成几千条元素的 `equals`，
         * 而 Int 比较是 O(1) —— 后台算完那一刻列表能可靠刷新，又不用比对全表。
         */
        val sortRevision: Int = 0,
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
        val deviceWarning: String? = null,
        /**
         * 资源库内容的版本号（只在 [assets] 真正变化时自增）。
         *
         * UI 侧用它（而不是 [assets] 本身）当 remember / LaunchedEffect 的 key：
         * 导入进度每秒会刷新好几次 State，但资源库内容没变——按 Map 做结构比较
         * 对几千条资源来说太贵，按 Int 比较则是 O(1)。
         */
        val libraryRevision: Int = 0,
        /**
         * 资源包条目列表的版本号（只在 [entries] 变化时自增）。
         *
         * 与 [libraryRevision] 同理：列表页要按它做 remember，
         * 否则每次重组都要在几千条条目上做一次结构比较。
         */
        val entriesRevision: Int = 0
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

        // ── 派生量（全部 lazy 缓存）─────────────────────────────────────────────
        // 这些量原来是纯 getter：每次访问都在 entries / assets 上重跑一遍全表扫描，有的还
        // 新建 List。而单次重组里它们会被读很多次（顶栏统计、Tab 标题、countSuffix、筛选项
        // chips、卡片…），在 8000 条 entries 的规模下等于成百上千次多余的 O(N)；其中
        // filteredAssets 每次返回新实例，还会让 LibraryTab 的 LaunchedEffect(assets) 反复重启。
        // State 是 data class + copy 的不可变快照，缓存随实例走、替换即失效，不需要手动清理。

        /** 当前类型筛选后的资源包列表 */
        val filteredEntries: List<GameBundleEntry> by lazy {
            kindFilter?.let { kind -> entries.filter { it.kind == kind } } ?: entries
        }

        fun assetsOf(kind: GameAssetKind?): List<GameAssetInfo> =
            if (kind == null) {
                GameAssetKind.ordered.flatMap { assets[it].orEmpty() }
            } else {
                assets[kind].orEmpty()
            }

        /** 当前类型筛选后的已导入资源（同一实例返回，稳定引用） */
        val filteredAssets: List<GameAssetInfo> by lazy { assetsOf(kindFilter) }

        /** 已导入（含可更新）的条目数 */
        val importedCount: Int by lazy {
            entries.count {
                it.state == GameBundleEntry.State.IMPORTED || it.state == GameBundleEntry.State.CHANGED
            }
        }

        /** 待导入：从未导入过 + 上次失败。CHANGED 不算（已导入，只是可更新） */
        val pendingCount: Int by lazy {
            entries.count {
                it.state == GameBundleEntry.State.NEW || it.state == GameBundleEntry.State.FAILED
            }
        }

        /** 可更新（已导入但源包/管线变化） */
        val updatableCount: Int by lazy { entries.count { it.state == GameBundleEntry.State.CHANGED } }

        val failedCount: Int by lazy { entries.count { it.state == GameBundleEntry.State.FAILED } }

        val totalAssets: Int by lazy { assets.values.sumOf { it.size } }

        /** 资源包按类型的条目数（一次分组，供筛选 chip 复用） */
        private val entryCountByKind: Map<GameAssetKind, Int> by lazy {
            entries.groupingBy { it.kind }.eachCount()
        }

        fun countOf(kind: GameAssetKind?): Int =
            if (kind == null) entries.size else entryCountByKind[kind] ?: 0

        fun importedCountOf(kind: GameAssetKind?): Int =
            if (kind == null) totalAssets else assets[kind].orEmpty().size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var importJob: Job? = null
    private var cancelRequested = false

    /** 皮肤名预热任务（合并并发触发用） */
    private var skinNameWarmupJob: Job? = null

    /** 已预热过的 baseName：重复触发只处理增量（受 [skinNameWarmupLock] 保护） */
    private val warmedSkinNames = HashSet<String>(256)
    private val skinNameWarmupLock = Any()

    /** 预热任务运行期间又有新请求时置位，结束后补跑一次 */
    private var skinNameWarmupPending = false

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
                nameEpoch++
                refreshSortedLists()
                requestSkinNames()
            }
        }
        viewModelScope.launch {
            // 真实换装名就绪后刷新显示名：把「换装N」升级为 wiki 皮肤名
            skinNameRegistry.skinSequences.collect { sequences ->
                _state.update { it.copy(skinNameSequences = sequences) }
                nameEpoch++
                refreshSortedLists()
            }
        }
        viewModelScope.launch {
            // 排序方案是纯 UI 偏好：读不到/脏数据就保持默认（名称正序），不影响扫描与导入
            settings.gameImportSort.collect { raw ->
                val restored = GameSortOrder.parse(raw) ?: return@collect
                if (_state.value.sortOrder != restored) {
                    _state.update { it.copy(sortOrder = restored) }
                    refreshSortedLists()
                }
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
            is GameImportIntent.SetSortOrder -> updateSortOrder(intent.order)
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
     * 为当前列表里出现的舰名拉取真实换装名（wiki 台词表），并预热皮肤名。
     *
     * 注册表内部按舰名去重、失败允许重试，重复调用零网络成本；
     * 就绪后经 [Live2dNameResolver.skinNameProvider] 自动把「换装N」升级为真实皮肤名。
     *
     * 资源规模可达 8000+ 个 id，原实现直接在 viewModelScope（Main）里同步跑完整个循环：
     * 建 8000 元素的 set + 8000 次 [Live2dNameResolver.baseShipName] 反查，
     * 全部压在提交线程上，正是「扫描结束的那一帧卡一下」的来源。
     *
     * 现在做了三件事：
     * 1. 挪到 [Dispatchers.Default]（反查是纯 CPU，不占 IO 线程也不占主线程）；
     * 2. 记住已预热的 baseName，重复触发（拼音表刷新 / 每次 refreshLibrary）只处理增量；
     * 3. 合并并发触发：进度更新期间连续调用不会叠出多条同样的任务。
     */
    private fun requestSkinNames() {
        if (skinNameWarmupJob?.isActive == true) {
            // 已有任务在跑：记一笔，等它跑完补齐新 id（不重复排队、也不丢请求）
            skinNameWarmupPending = true
            return
        }
        if (allAssetIds().isEmpty()) return
        skinNameWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            // 首轮 + 「期间又来了新请求」的补跑；用 while 而不是递归调用，避免
            // isActive 判定把自己当成「已有任务」再次推迟
            warmSkinNames(allAssetIds())
            while (skinNameWarmupPending) {
                skinNameWarmupPending = false
                warmSkinNames(allAssetIds())
            }
        }
    }

    /** entries + assets 里出现过的全部资源 id 快照 */
    private fun allAssetIds(): Set<String> {
        val current = _state.value
        return buildSet {
            current.entries.forEach { add(it.assetId) }
            current.assets.values.forEach { list -> list.forEach { add(it.id) } }
        }
    }

    /** 对一批 id 反查 baseName 并请求 wiki 换装名；已预热过的 baseName 直接跳过 */
    private fun warmSkinNames(ids: Set<String>) {
        ids.forEach { id ->
            val base = nameResolver.baseShipName(id) ?: return@forEach
            val fresh = synchronized(skinNameWarmupLock) { warmedSkinNames.add(base) }
            if (fresh) skinNameRegistry.request(base)
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 显示名数据（舰名拼音 / wiki 换装名）的版本号。
     *
     * 一到就自增，排序键缓存随之失效 —— 显示名变了，排序结果和卡片标题都得跟着变。
     */
    @Volatile
    private var nameEpoch = 0

    /**
     * 显示名增量缓存。
     *
     * 反查舰名（拼音表 / wiki 换装名）是排序键生成里最贵的一步。批量导入时列表每次刷新
     * 都重算全表就是几千次反查；这里按 path / `kind/id` 缓存，重新扫描时只补算新条目，
     * 名字数据（[nameEpoch]）一变就整体作废。
     */
    private val nameCacheLock = Any()
    private val bundleNameCache = HashMap<String, String>()
    private val assetNameCache = HashMap<String, String>()
    private var nameCacheEpoch = -1

    /**
     * 上一批排序键 + 名字签名。
     *
     * 签名是「全部 (键, 名字)」的无序哈希：导入只改条目状态（待导入 → 已导入）时签名不变，
     * 于是 [GameAssetSorting.keysOf] 的 ICU 折键可以整批复用，只重排已有键。
     */
    private class BundleKeys(
        val signature: Long,
        val nameEpoch: Int,
        val names: Map<String, String>,
        val keys: List<GameAssetSortKey<GameBundleEntry>>
    )

    /** 上一批排序键（已导入资源）+ 它对应的名字签名 */
    private class AssetKeys(
        val signature: Long,
        val nameEpoch: Int,
        val names: Map<String, String>,
        val keys: List<GameAssetSortKey<GameAssetInfo>>
    )

    @Volatile
    private var bundleKeysCache: BundleKeys? = null

    @Volatile
    private var assetKeysCache: AssetKeys? = null

    private var sortJob: Job? = null

    /**
     * 重算「显示名表 + 排序键 + 排序结果」，全部在 [Dispatchers.Default] 上做。
     *
     * 为什么不在 UI 层 remember 里排：显示名要反查舰名（拼音表 + wiki 皮肤名），
     * 排序键要把名字折成 ICU 的 [java.text.CollationKey]，8000 条资源做一遍是几十毫秒的
     * CPU 活儿。原来这些都压在提交线程上，表现为「扫描结束那一下卡顿」「切排序顿一下」。
     *
     * 两点关键设计：
     * 1. **键缓存**：数据版本（[State.entriesRevision] / [State.libraryRevision]）和名字版本
     *    （[nameEpoch]）都没变时直接复用上一批键 —— 切升/降序、切字段、切 Tab 都不再跑 ICU；
     * 2. **合并触发**：批量导入时每导完一个条目都会 [markEntry] 一次，这里用
     *    [SORT_DEBOUNCE_MS] 的窗口把连续刷新合并成一次（任务被取消即重来）。
     *
     * 结果写回前会核对版本：期间数据又变了就丢弃这批结果，交给后一次刷新，
     * 避免旧快照覆盖新列表（列表错位比慢一点严重得多）。
     */
    private fun refreshSortedLists() {
        val snapshot = _state.value
        val entries = snapshot.entries
        val assets = snapshot.assets
        val entriesRev = snapshot.entriesRevision
        val libraryRev = snapshot.libraryRevision
        val epoch = nameEpoch
        val order = snapshot.sortOrder

        sortJob?.cancel()
        sortJob = viewModelScope.launch(Dispatchers.Default) {
            delay(SORT_DEBOUNCE_MS)

            val bundleNames = buildBundleNames(entries, epoch)
            val bundleSignature = bundleSignatureOf(entries, bundleNames)
            val bundles = bundleKeysCache
                ?.takeIf { it.signature == bundleSignature && it.nameEpoch == epoch }
                ?: buildBundleKeys(entries, bundleNames, bundleSignature, epoch)
                    .also { bundleKeysCache = it }

            val flatAssets = flattenAssets(assets)
            val libraryNames = buildAssetNames(flatAssets, epoch)
            val librarySignature = assetSignatureOf(flatAssets, libraryNames)
            val library = assetKeysCache
                ?.takeIf { it.signature == librarySignature && it.nameEpoch == epoch }
                ?: buildAssetKeys(flatAssets, libraryNames, librarySignature, epoch)
                    .also { assetKeysCache = it }

            // 排序键里带的是生成那一刻的条目快照。导入成功 / 失败只改 entry.state（不改 mtime、不改路径），
            // 签名不变 → 键命中缓存 → 若直接把键里的 item 发出去，列表会一直显示旧的「待导入 / 导入」按钮。
            // 所以这里只取「顺序」，再按 tieBreaker 换回当前状态里的条目对象。
            val bundleFresh = HashMap<String, GameBundleEntry>(entries.size * 2).apply {
                for (entry in entries) put(entry.path, entry)
            }
            val sortedEntries = GameAssetSorting.sortedKeys(bundles.keys, order)
                .map { key -> bundleFresh[key.tieBreaker] ?: key.item }

            val assetFresh = HashMap<String, GameAssetInfo>(flatAssets.size * 2).apply {
                for (asset in flatAssets) put(assetKey(asset), asset)
            }
            val sortedAssets = GameAssetSorting.sortedKeys(library.keys, order)
                .map { key -> assetFresh[key.tieBreaker] ?: key.item }

            _state.update { current ->
                if (current.entriesRevision != entriesRev ||
                    current.libraryRevision != libraryRev ||
                    current.sortOrder != order ||
                    nameEpoch != epoch
                ) {
                    current
                } else {
                    current.copy(
                        sortedEntries = sortedEntries,
                        entryNames = bundles.names,
                        sortedAssets = sortedAssets,
                        assetNames = library.names,
                        sortRevision = current.sortRevision + 1
                    )
                }
            }
        }
    }

    /** 资源库按类型摊平成一条列表（顺序固定为 [GameAssetKind.ordered]，结果可复现） */
    private fun flattenAssets(assets: Map<GameAssetKind, List<GameAssetInfo>>): List<GameAssetInfo> {
        val total = assets.values.sumOf { it.size }
        val flat = ArrayList<GameAssetInfo>(total)
        for (kind in GameAssetKind.ordered) {
            assets[kind]?.let(flat::addAll)
        }
        return flat
    }

    /**
     * 增量算出资源包的显示名表：只有新路径才去反查舰名。
     *
     * 排序键的生成依赖这张表，所以它每次刷新都要有；但「反查」不必每次都有。
     */
    private fun buildBundleNames(
        entries: List<GameBundleEntry>,
        epoch: Int
    ): Map<String, String> = synchronized(nameCacheLock) {
        resetNameCachesIfStale(epoch)
        val names = HashMap<String, String>(entries.size * 2)
        for (entry in entries) {
            val cached = bundleNameCache[entry.path]
            val name = cached ?: displayNameOfEntry(entry).ifBlank { entry.displayName }
            if (cached == null) bundleNameCache[entry.path] = name
            names[entry.path] = name
        }
        // 已删除的资源包（重装游戏 / 清缓存）不该一直留在缓存里
        if (bundleNameCache.size > entries.size * 2) {
            bundleNameCache.keys.retainAll(names.keys)
        }
        names
    }

    /** 增量算出已导入资源的显示名表（只给新 key 反查舰名 / wiki 换装名） */
    private fun buildAssetNames(
        assets: List<GameAssetInfo>,
        epoch: Int
    ): Map<String, String> = synchronized(nameCacheLock) {
        resetNameCachesIfStale(epoch)
        val names = HashMap<String, String>(assets.size * 2)
        for (asset in assets) {
            val key = assetKey(asset)
            val cached = assetNameCache[key]
            val name = cached ?: displayNameFor(asset.kind, asset.id).ifBlank { asset.id }
            if (cached == null) assetNameCache[key] = name
            names[key] = name
        }
        if (assetNameCache.size > assets.size * 2) {
            assetNameCache.keys.retainAll(names.keys)
        }
        names
    }

    private fun resetNameCachesIfStale(epoch: Int) {
        if (nameCacheEpoch == epoch) return
        nameCacheEpoch = epoch
        bundleNameCache.clear()
        assetNameCache.clear()
    }

    /**
     * 名字表的无序哈希。
     *
     * 导入只改条目状态（待导入 → 已导入）时名字表内容不变，签名也不变 ——
     * 于是整批 ICU 排序键可以复用，只重排已有键。
     */
    private fun signatureOf(names: Map<String, String>): Long {
        var signature = 0L
        for ((key, value) in names) {
            signature += (key.hashCode().toLong() shl 32) xor
                (value.hashCode().toLong() and 0xFFFFFFFFL)
        }
        return signature
    }

    /**
     * 资源包的排序键签名：名字表 + 每条的时间与路径。
     *
     * 时间必须参与签名 —— 资源包被游戏重新下载后 mtime 变了，按时间排序的键不能沿用旧值。
     */
    private fun bundleSignatureOf(
        entries: List<GameBundleEntry>,
        names: Map<String, String>
    ): Long {
        var signature = signatureOf(names)
        for (entry in entries) {
            signature = signature * 31 + entry.modified
            signature = signature * 31 + entry.path.hashCode()
        }
        return signature
    }

    /** 已导入资源的排序键签名：名字表 + 每条的时间与 kind/id */
    private fun assetSignatureOf(
        assets: List<GameAssetInfo>,
        names: Map<String, String>
    ): Long {
        var signature = signatureOf(names)
        for (asset in assets) {
            signature = signature * 31 + asset.modifiedAt
            signature = signature * 31 + asset.id.hashCode()
        }
        return signature
    }

    private fun buildBundleKeys(
        entries: List<GameBundleEntry>,
        names: Map<String, String>,
        signature: Long,
        epoch: Int
    ): BundleKeys = BundleKeys(
        signature = signature,
        nameEpoch = epoch,
        names = names,
        keys = GameAssetSorting.bundleKeys(entries) { entry ->
            names[entry.path] ?: entry.displayName
        }
    )

    private fun buildAssetKeys(
        assets: List<GameAssetInfo>,
        names: Map<String, String>,
        signature: Long,
        epoch: Int
    ): AssetKeys = AssetKeys(
        signature = signature,
        nameEpoch = epoch,
        names = names,
        keys = GameAssetSorting.assetKeys(assets) { asset ->
            names[assetKey(asset)] ?: asset.id
        }
    )

    /** 已导入资源的稳定键：同一 id 可能同时存在于立绘与动态皮肤，裸 id 会撞车 */
    private fun assetKey(asset: GameAssetInfo): String = "${asset.kind.key}/${asset.id}"

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
            _state.update { it.copy(scanning = true, error = null, scanStatus = null) }
            try {
                val roots = scanner.discoverRoots()
                if (roots.isEmpty()) {
                    _state.update {
                        it.copy(
                            scanning = false,
                            error = "未找到碧蓝航线资源目录（请确认游戏已安装并下载过资源，然后点「重试」重新扫描）"
                        )
                    }
                    refreshLibrary()
                    return@launch
                }
                roots.forEach { (kind, path) -> index.rememberRoot(kind, path) }
                val entries = scanner.scan(roots) { found ->
                    // scanner 的进度回调在 IO 线程触发，StateFlow.update 线程安全
                    _state.update { it.copy(scanStatus = "已发现 $found 个资源包…") }
                }
                _state.update {
                    it.copy(
                        scanning = false,
                        scanStatus = null,
                        entries = entries,
                        entriesRevision = it.entriesRevision + 1,
                        roots = roots,
                        lastScanAt = System.currentTimeMillis()
                    )
                }
                refreshSortedLists()
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
                        scanStatus = null,
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
            _state.update { it.copy(assets = assets, libraryRevision = it.libraryRevision + 1) }
            refreshSortedLists()
            requestSkinNames()
        }
    }

    // ------------------------------------------------------------------ 导入

    private fun importEntries(targets: List<GameBundleEntry>) {
        if (targets.isEmpty()) return
        // 自愈：上一次导入协程已经结束（正常跑完 / 被停止 / 被取消），但进度条因为某条异常路径残留下来，
        // 会让入口守卫把之后所有的「导入 / 重试 / 更新 / 全部导入」全部静默拦住 —— 先把残影清掉。
        if (_state.value.progress != null && importJob?.isActive != true) {
            _state.update { it.copy(progress = null) }
        }
        if (_state.value.progress != null) return
        cancelRequested = false
        importJob = viewModelScope.launch {
            val success = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            // try/finally 保证「清进度 + 刷新库」一定会执行：
            // 早期版本在用户点「停止」时 return 直接跳过了收尾，进度条永久卡在最后一帧，
            // 之后卡片按钮全部因为 busy = progress != null 而变灰，看起来就是「导入完还是导入按钮」。
            try {
                for ((position, entry) in targets.withIndex()) {
                    // 用户点了停止：跳出循环（不是 return），finally 依然会收尾
                    if (cancelRequested) break
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
                                            (imported.kind to (list + imported)),
                                        libraryRevision = current.libraryRevision + 1
                                    )
                                }
                            }
                            refreshSortedLists()
                        }
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        // 页面销毁导致协程取消：取消不是导入失败，
                        // 剩余条目保持原状态，索引也不会被写成 FAILED
                        throw c
                    } catch (t: Throwable) {
                        val reason = t.message ?: t.javaClass.simpleName
                        failed += entry.fileName to reason
                        markEntry(entry.path, GameBundleEntry.State.FAILED, reason)
                    }
                }
            } finally {
                _state.update {
                    it.copy(
                        progress = null,
                        batchMessage = if (success.isEmpty() && failed.isEmpty() && cancelRequested) {
                            null
                        } else {
                            buildBatchMessage(success.size, failed, cancelRequested)
                        }
                    )
                }
                // 协程可能已被取消，finally 里直接 suspend 会立刻抛 CancellationException；
                // 另起一个协程收尾，页面还在就校准库、已销毁就静默丢弃
                viewModelScope.launch { refreshLibrary() }
            }
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
                entriesRevision = current.entriesRevision + 1,
                entries = current.entries.map {
                    if (it.path == path) {
                        it.copy(state = state, error = error, assetId = assetId ?: it.assetId)
                    } else {
                        it
                    }
                }
            )
        }
        refreshSortedLists()
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
                    },
                    libraryRevision = if (ok) it.libraryRevision + 1 else it.libraryRevision
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

    /**
     * 切换排序方案：先更新界面（方案芯片立刻变），排序结果由后台任务重排后写回。
     *
     * 落盘失败（DataStore IO / 空间不足）只影响「下次进页面记不住顺序」，
     * 不该打断本次排序，因此异常吞掉只留日志。
     */
    private fun updateSortOrder(order: GameSortOrder) {
        if (order == _state.value.sortOrder) return
        _state.update { it.copy(sortOrder = order) }
        refreshSortedLists()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { settings.setGameImportSort(order.storageKey) }
                .onFailure { Log.w(TAG, "排序方案保存失败：${order.storageKey}", it) }
        }
    }

    private companion object {
        private const val TAG = "GameImportVM"

        /**
         * 排序刷新的合并窗口（毫秒）。
         *
         * 批量导入时每导完一个条目都会刷新一次列表状态，1000 个文件就是 1000 次重排；
         * 用 50ms 窗口合并：用户感知不到延迟，后台少做上千次无用功。
         */
        private const val SORT_DEBOUNCE_MS = 50L
    }
}
