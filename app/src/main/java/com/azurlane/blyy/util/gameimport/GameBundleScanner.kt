package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import com.azurlane.blyy.shizuku.IBlyyShellService
import com.azurlane.blyy.util.shizuku.ShizukuBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 通道状态（UI 直接消费）。
 *
 * [SERVICE_UNREACHABLE] 是「权限给了、Shizuku 也在跑，但 UserService 绑不上 / 不应答」——
 * 典型场景是 release 包被裁剪掉了 `BlyyShellService` 的构造器（见 proguard-rules）。
 * 单独列出来是为了别再把这种情况显示成「已连接」（用户会以为已经能扫描了）。
 */
enum class ShellState { UNKNOWN, NOT_RUNNING, NO_PERMISSION, SERVICE_UNREACHABLE, READY }

/**
 * 扫描结果中的单个资源包。
 *
 * [state] 决定按钮语义：新增→导入；已导入→查看；有更新→重新导入；失败→重试并展示原因。
 */
data class GameBundleEntry(
    val path: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val kind: GameAssetKind,
    val assetId: String,
    val state: State,
    val importedAt: Long = 0L,
    val error: String? = null,
    /**
     * 同一次导入需要一起复制的附属包（绝对路径）。
     *
     * 静态立绘 = `painting/<名字>` prefab + `_rw` / `_front` 图层 + UI 图集：
     * 只复制 `_tex` 主包时，人物层和布局信息都拿不到，产物只能是背景图。
     */
    val companions: List<String> = emptyList()
) {
    enum class State { NEW, IMPORTED, CHANGED, FAILED }

    val displayName: String get() = fileName.substringBeforeLast('.')
    val kindLabel: String get() = kind.shortLabel
}

/**
 * 游戏资源包扫描器（四类资源统一）。
 *
 * 流程：Shizuku 列举 `AssetBundles/<kind>` → 用持久化索引判断已导入/有更新
 * （大小 + 修改时间 + 提取管线版本）→ 只对新增/变化的包执行导入。
 * 目录不存在（没装游戏 / 没下载该类资源）时跳过该类型，不阻塞其他类型。
 */
@Singleton
class GameBundleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: ShizukuBridge,
    private val index: GameImportIndex,
    private val library: GameAssetLibrary,
    private val importer: GameBundleImporter
) {
    companion object {
        private const val TAG = "GameBundleScanner"

        /**
         * 已知的碧蓝航线包名（按优先级）。
         *
         * 官服/渠道服/外服包名不同，只写死 `com.bilibili.azurlane` 会导致
         * 「非官服扫描不到资源」；这里覆盖常见服务器，另外还有
         * [discoverBundlesRoots] 的 `Android/data/<包名>` 自动发现兜底。
         */
        private val KNOWN_PACKAGES = listOf(
            "com.bilibili.azurlane",   // 国服（B 服 / 多数渠道共用）
            "com.YoStarEN.AzurLane",   // 国际服
            "com.YoStarJP.AzurLane",   // 日服
            "com.YoStarKR.AzurLane",   // 韩服
            "com.hkmanjuu.azurlane"    // 台服
        )

        /** 同一块存储的两种挂载路径（外部存储 / legacy 路径） */
        private val STORAGE_PREFIXES = listOf(
            "/sdcard/Android/data",
            "/storage/emulated/0/Android/data"
        )

        /** 自动发现时列举的包数量上限 */
        private const val MAX_PACKAGE_SCAN = 600

        /** 资源包扩展名（碧蓝部分版本没有扩展名，允许空扩展名） */
        private val BUNDLE_EXTENSIONS = setOf(
            "ys", "unity3d", "bundle", "ab", "assetbundle", "asset", "bin"
        )

        /** 明显不是资源的文件名片段 */
        private val SKIP_NAME_HINTS = listOf(
            "shader", "dependencies", "manifest", "hashes", "version-", "l2dshader", "l2dmask"
        )

        /** 过小的文件（多为预制体/配置）直接跳过：立绘 _tex 最小也有 200KB+ */
        private const val MIN_BUNDLE_BYTES = 128L * 1024

        private const val MAX_SCAN_DEPTH = 2

        /**
         * 分页列举参数：单页条目 / 字节预算。
         *
         * Binder 单次回复约 1MB 上限，`AssetBundles/painting` 有 1 万+ 文件
         * （一次列举 ≈1.3MB）会直接把 shell 进程打挂（DeadObjectException）。
         * 600 条 × ~90 字节 ≈ 54KB，稳妥且往返次数可控。
         */
        private const val LIST_PAGE_SIZE = 600
        private const val LIST_PAGE_BYTES = 64_000

        /** 单次扫描最多翻页数（600 × 200 = 12 万条，足够覆盖全部资源包） */
        private const val LIST_MAX_PAGES = 200

        /** 立绘装饰层所在的共享 UI 图集（相对 AssetBundles 根） */
        private const val UI_ATLAS = "commonui_atlas.ys"
    }

    // ------------------------------------------------------------------ 通道状态

    fun shellState(): ShellState = when {
        !bridge.isAvailable() -> ShellState.NOT_RUNNING
        !bridge.isPermissionGranted() -> ShellState.NO_PERMISSION
        else -> ShellState.READY
    }

    /**
     * 真实探测通道：可用性 → 权限 → **一次 Binder 往返**（`ping`）。
     *
     * 只判断「有权限」是不够的：Shizuku 里 `bindUserService` 是异步的，绑定失败
     * 只会让等待超时。release 包如果被 R8 裁掉服务实现的构造器/AIDL 方法，
     * 表现就是「权限给了、ping 通、却永远扫不到目录」——必须把这条路径显式区分出来。
     *
     * @return 状态 + 服务端应答文本（失败时是原因）
     */
    suspend fun probeState(): Pair<ShellState, String?> = withContext(Dispatchers.IO) {
        // 前两步是便宜的本地判断，先短路掉；只有「看起来就绪」才值得付一次绑定开销
        when (shellState()) {
            ShellState.NOT_RUNNING -> return@withContext ShellState.NOT_RUNNING to null
            ShellState.NO_PERMISSION -> return@withContext ShellState.NO_PERMISSION to null
            else -> Unit
        }
        try {
            val reply = bridge.requireService().ping()
            ShellState.READY to reply.ifBlank { "blyy-shell" }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku 通道探测失败：${t.javaClass.simpleName} ${t.message}")
            ShellState.SERVICE_UNREACHABLE to (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 丢弃缓存的服务代理并允许重新绑定（用户点「重试」时用） */
    fun resetShell() = bridge.reset()

    fun versionLabel(): String? = bridge.versionLabel()

    fun requestPermission(): Boolean = bridge.requestPermission()

    fun addPermissionListener(listener: (Int, Int) -> Unit) = bridge.addPermissionResultListener(listener)

    fun removePermissionListener(listener: (Int, Int) -> Unit) {
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener) }
    }

    fun release() = bridge.release()

    /** 模型中是否还存在该资源（索引自检用） */
    suspend fun assetExists(kind: GameAssetKind, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { library.find(kind, id) != null }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 探测各类型资源目录（支持所有碧蓝航线服务器/渠道包）。
     *
     * 顺序：
     * 1. 已知包名（[KNOWN_PACKAGES]）逐个探测 `files/AssetBundles`；
     * 2. 都没命中时，列举 `Android/data/<包名>` 自动发现带 `files/AssetBundles` 的包
     *    （覆盖各类渠道服、私服、改包名客户端）；
     * 3. 在找到的资源根下按 `live2d / char / spinepainting / painting` 探测子目录。
     */
    suspend fun discoverRoots(): Map<GameAssetKind, String> = withContext(Dispatchers.IO) {
        // 这里**不能**把异常吞掉：绑定失败与「游戏没装资源」是两回事，
        // 吞掉之后用户只会看到「未找到资源目录」，永远不知道是通道坏了。
        val service = bridge.requireService()
        val bundlesRoots = discoverBundlesRoots(service)
        if (bundlesRoots.isEmpty()) return@withContext emptyMap()

        val found = LinkedHashMap<GameAssetKind, String>()
        for (bundlesRoot in bundlesRoots) {
            for (kind in GameAssetKind.ordered) {
                if (found.containsKey(kind)) continue
                val path = "$bundlesRoot/${kind.bundleDir}"
                if (runCatching { service.exists(path) }.getOrDefault(false)) {
                    found[kind] = path
                }
            }
            if (found.size == GameAssetKind.ordered.size) break
        }
        // 兜底：部分客户端把资源目录名改成别的写法，按 AssetBundles 一级目录再认一次
        if (found.size < GameAssetKind.ordered.size) {
            val listing = runCatching { service.listFiles(bundlesRoots.first(), 1, 400) }
                .getOrNull()
                .orEmpty()
            listing.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0] == "d") {
                    val kind = GameAssetKind.fromBundlePath(parts[1]) ?: return@forEach
                    if (!found.containsKey(kind)) found[kind] = parts[1]
                }
            }
        }
        found
    }

    /** 找出设备上所有「含 `files/AssetBundles`」的游戏数据目录 */
    private fun discoverBundlesRoots(service: IBlyyShellService): List<String> {
        val roots = LinkedHashMap<String, String>() // package -> AssetBundles 绝对路径
        for (pkg in KNOWN_PACKAGES) {
            for (prefix in STORAGE_PREFIXES) {
                val path = "$prefix/$pkg/files/AssetBundles"
                if (runCatching { service.exists(path) }.getOrDefault(false)) {
                    roots[pkg] = path
                    break
                }
            }
        }
        if (roots.isNotEmpty()) return roots.values.toList()

        // 自动发现：列举 Android/data 一级目录，挑出带 files/AssetBundles 的包
        val listing = runCatching { service.listFiles(STORAGE_PREFIXES.first(), 1, MAX_PACKAGE_SCAN) }
            .getOrNull()
            .orEmpty()
        listing.lineSequence().forEach { line ->
            if (roots.isNotEmpty() && roots.size >= 8) return@forEach
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0] != "d") return@forEach
            val dir = parts[1]
            val candidate = "$dir/files/AssetBundles"
            if (runCatching { service.exists(candidate) }.getOrDefault(false)) {
                roots[dir.substringAfterLast('/')] = candidate
            }
        }
        Log.i(TAG, "自动发现资源目录：${roots.keys.joinToString()}")
        return roots.values.toList()
    }

    /**
     * 扫描已发现的资源目录，返回全部候选资源包及其状态。
     *
     * @param roots [discoverRoots] 的结果
     * @param onProgress 已解析的候选数量（用于 UI 提示）
     */
    suspend fun scan(
        roots: Map<GameAssetKind, String>,
        onProgress: (found: Int) -> Unit = {}
    ): List<GameBundleEntry> = withContext(Dispatchers.IO) {
        if (roots.isEmpty()) return@withContext emptyList()
        val tStart = android.os.SystemClock.elapsedRealtime()
        var tListAll = tStart
        var tListDone = tStart
        val known = index.load().entries.associateBy { it.path }
        Log.d(TAG, "扫描阶段[读索引] ${known.size} 条 ${android.os.SystemClock.elapsedRealtime() - tStart}ms")
        val tLib = android.os.SystemClock.elapsedRealtime()
        val importedIds = library.scanAll().mapValues { (_, list) -> list.map { it.id }.toSet() }
        Log.d(TAG, "扫描阶段[本地库] ${android.os.SystemClock.elapsedRealtime() - tLib}ms")

        val entries = ArrayList<GameBundleEntry>()
        // 同类资源可能有多个目录（过场图的 loadingbg / loadingbg_hx）：
        // 打包成 job 后并发列举，服务端按 root 缓存多份快照
        val jobs = roots.flatMap { (kind, root) ->
            buildList {
                add(ListingJob(kind, root, ""))
                kind.siblingDirs.forEach { dir ->
                    add(ListingJob(kind, siblingDirPath(root, dir), dir.substringAfterLast('_')))
                }
            }
        }
        tListAll = android.os.SystemClock.elapsedRealtime()
        val listed: List<Pair<ListingJob, List<String>>> = coroutineScope {
            jobs.map { job ->
                async(Dispatchers.IO) {
                    val tList = android.os.SystemClock.elapsedRealtime()
                    val lines = runCatching { listAllPages(job.root) }.getOrNull().orEmpty()
                    Log.d(
                        TAG,
                        "扫描阶段[列举 ${job.kind.key}${if (job.tag.isEmpty()) "" else "/${job.tag}"}] " +
                            "${lines.size} 行 ${android.os.SystemClock.elapsedRealtime() - tList}ms"
                    )
                    job to lines
                }
            }.awaitAll()
        }
        tListDone = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "扫描阶段[并发列举] 完成 ${tListDone - tListAll}ms")

        for ((kind, root) in roots) {
            for ((job, lines) in listed.filter { it.first.kind == kind }) {
                // 一遍扫描同时产出「候选包」与「文件名索引」（立绘需要后者挑附属包）
                val parsed = parseListing(lines, kind, needNameIndex = kind == GameAssetKind.PAINTING)
                val candidates = parsed.candidates
                onProgress(entries.size + candidates.size)
                val nameIndex = parsed.nameIndex
                val uiAtlases = if (kind == GameAssetKind.PAINTING) uiAtlasCandidates(root) else emptyList()
                // 附属包映射一次算完（O(N)）：逐个候选扫全目录是 O(N²)，实测让扫描卡数分钟
                val tCompanions = android.os.SystemClock.elapsedRealtime()
                val companionMap = if (kind == GameAssetKind.PAINTING) {
                    PaintingCompanionRules.companionsForAll(nameIndex, uiAtlases)
                } else {
                    emptyMap()
                }
                if (kind == GameAssetKind.PAINTING) {
                    Log.d(
                        TAG,
                        "扫描阶段[附属包索引] ${companionMap.size} 项 " +
                            "${android.os.SystemClock.elapsedRealtime() - tCompanions}ms"
                    )
                }

                for (file in candidates.sortedBy { it.name.lowercase() }) {
                    val record = known[file.path]
                    val baseId = GameAssetNaming.assetIdOf(file.name, kind)
                    // 兄弟目录（如 loadingbg_hx）的资源 id 带目录后缀，避免与主目录撞名
                    val fallbackId = if (job.tag.isEmpty()) baseId else "${baseId}_${job.tag}"
                    val assetId = record?.takeIf { it.assetKind == kind }?.assetId ?: fallbackId
                    val state = resolveState(record, kind, assetId, file, importedIds[kind].orEmpty())
                    entries += GameBundleEntry(
                        path = file.path,
                        fileName = file.name,
                        size = file.size,
                        modified = file.modified,
                        kind = kind,
                        assetId = assetId,
                        state = state,
                        importedAt = record?.importedAt ?: 0L,
                        error = if (state == GameBundleEntry.State.FAILED) record?.error else null,
                        companions = companionMap[file.name.substringBeforeLast('.').lowercase()].orEmpty()
                    )
                }
            }
        }


        // 清理已不存在的路径记录（只清理本次扫描覆盖的根下的记录）
        runCatching {
            val scannedRoots = roots.values.toList() +
                roots.values.flatMap { root ->
                    roots.keys.flatMap { kind -> kind.siblingDirs.map { siblingDirPath(root, it) } }
                }
            val keep = known.keys.filter { path ->
                scannedRoots.none { path.startsWith("$it/") }
            }.toSet() + entries.map { it.path }
            val stale = known.keys - keep
            if (stale.isNotEmpty()) {
                index.replaceAll(
                    records = emptyList(),
                    keepPaths = keep
                )
                Log.i(TAG, "清理失效索引 ${stale.size} 条")
            }
        }

        Log.i(
            TAG,
            "扫描完成：${entries.size} 条，耗时 ${android.os.SystemClock.elapsedRealtime() - tStart}ms" +
                "（其中列举 ${tListDone - tListAll}ms）"
        )
        entries.sortedWith(compareBy({ GameAssetKind.ordered.indexOf(it.kind) }, { it.displayName.lowercase() }))
    }

    /**
     * 分页读完一个目录（远端进程死亡时自动重绑重试一次）。
     *
     * @return null 表示列举失败（日志里已有原因），调用方跳过该类型即可
     */
    private suspend fun listAllPages(root: String): List<String>? {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return listAllPagesOnce(bridge.requireService(), root)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "列举失败（第 ${attempt + 1} 次）：$root ${t.javaClass.simpleName}")
                // 远端进程可能因大目录等原因死亡：丢弃代理，下次 requireService 会重新绑定
                bridge.invalidate()
                if (t !is android.os.DeadObjectException && t !is android.os.RemoteException) {
                    return null
                }
            }
        }
        Log.w(TAG, "列举失败：$root", lastError)
        return null
    }

    /** 按页读取，直到服务端报告取完 */
    private fun listAllPagesOnce(service: IBlyyShellService, root: String): List<String> {
        val out = ArrayList<String>(512)
        var offset = 0
        var total = -1
        var pages = 0
        while (pages < LIST_MAX_PAGES) {
            val raw = service.listFilesPage(
                root,
                MAX_SCAN_DEPTH,
                offset,
                LIST_PAGE_SIZE,
                LIST_PAGE_BYTES,
                offset == 0
            )
            if (raw.isBlank()) break
            var consumed = 0
            raw.lineSequence().forEach { line ->
                when {
                    line.startsWith("#total=") ->
                        total = line.removePrefix("#total=").toIntOrNull() ?: total

                    line.isBlank() -> Unit
                    else -> {
                        out += line
                        consumed++
                    }
                }
            }
            pages++
            offset += consumed
            if (consumed == 0) break
            if (total in 0..offset) break
        }
        return out
    }

    // ------------------------------------------------------------------ 导入

    /** 导入单个（来自 Shizuku 扫描的）资源包 */
    suspend fun importEntry(
        entry: GameBundleEntry,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): GameBundleImporter.Result = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "game_bundles").apply { mkdirs() }
        val local = File(cacheDir, "${entry.assetId}_${entry.size}.src")
        val companions = ArrayList<File>(entry.companions.size)
        try {
            onProgress(0.01f, "从游戏目录复制资源包")
            val copied = copyRemote(entry.path, local) { fraction ->
                onProgress(0.01f + fraction * 0.13f, "复制资源包 ${(fraction * 100).toInt()}%")
            }
            if (copied <= 0L) throw IOException("复制失败：0 字节")

            // 附属包（prefab / 人物层 / UI 图集）：单个失败只丢那一层，
            // 不能因为它整个立绘导入失败——没有 prefab 还能退化成单层网格还原。
            entry.companions.forEachIndexed { index, remote ->
                val name = remote.substringAfterLast('/')
                val target = File(cacheDir, "${entry.assetId}_${entry.size}_$index.src")
                onProgress(
                    0.14f + 0.05f * index / entry.companions.size.coerceAtLeast(1),
                    "复制附属包 $name"
                )
                runCatching { copyRemote(remote, target) {} }
                    .onSuccess { companions += target }
                    .onFailure {
                        // 附属包是可选的：UI 图集在不同客户端命名不同，
                        // 缺失只意味着少一层装饰，不影响整图
                        Log.d(TAG, "附属包不可用（跳过）：${remote.substringAfterLast('/')}")
                        runCatching { target.delete() }
                    }
            }

            importer.importLocal(
                bundleFile = local,
                sourcePath = entry.path,
                fileNameHint = entry.fileName,
                kindHint = entry.kind,
                sizeHint = entry.size,
                modifiedHint = entry.modified,
                paintingCompanions = companions,
                onProgress = { fraction, message -> onProgress(0.20f + fraction * 0.80f, message) }
            )
        } finally {
            runCatching { local.delete() }
            companions.forEach { runCatching { it.delete() } }
        }
    }

    // ------------------------------------------------------------------ 内部

    private data class RemoteFile(val path: String, val name: String, val size: Long, val modified: Long)

    /** 一次目录列举任务：同一类型的多个目录（`loadingbg` / `loadingbg_hx`）分别列举 */
    private data class ListingJob(val kind: GameAssetKind, val root: String, val tag: String)

    /** 同级目录路径（`painting` → `<AssetBundles>/loadingbg_hx`） */
    private fun siblingDirPath(root: String, dir: String): String {
        val base = root.trimEnd('/').substringBeforeLast('/')
        return "$base/$dir"
    }


    /**
     * 共享 UI 图集的候选路径。
     *
     * 官方客户端里这个包**没有扩展名**（`AssetBundles/ui/commonui_atlas`），
     * 部分客户端带 `.ys`；两份都试，缺失的那份在复制阶段被跳过。
     */
    private fun uiAtlasCandidates(root: String): List<String> {
        val base = root.trimEnd('/').substringBeforeLast('/')
        return listOf(
            "$base/ui/$UI_ATLAS",
            "$base/ui/${UI_ATLAS.removeSuffix(".ys")}"
        )
    }


    /**
     * 判定一个远端资源包的导入状态。
     *
     * 语义以「库里真的有这个资产」为最高优先级——只要索引记录与磁盘目录都在，
     * 就显示已导入，不再因为管线版本升级而把用户已经导入过的东西重新标成
     * 「待导入」（旧实现里 `pipelineVersion != 当前版本 → CHANGED`，
     * 每次发版只要动过提取器版本，整个资源库都会冒出「导入」按钮，
     * 用户看到的就是「明明已导入却还提示导入」）。
     *
     * 各状态含义：
     * - NEW：从未导入过（或索引丢失、库里也没有）；
     * - IMPORTED：已导入且源包未变化；
     * - CHANGED：已导入，但源包内容变了（大小/修改时间），或提取管线升级过
     *   ——界面文案是「可更新」，属于可选优化，不是失败；
     * - FAILED：上次导入失败（原因可查）。
     */
    private fun resolveState(
        record: GameImportRecord?,
        kind: GameAssetKind,
        assetId: String,
        file: RemoteFile,
        importedIds: Set<String>
    ): GameBundleEntry.State = when {
        record == null -> GameBundleEntry.State.NEW
        !record.isImported -> GameBundleEntry.State.FAILED
        record.assetKind != kind -> GameBundleEntry.State.NEW
        // 索引里有记录、但磁盘上资产已不在了（被删除/目录损坏）→ 需要重新导入
        record.assetId !in importedIds -> GameBundleEntry.State.NEW
        // 源包内容变化（大小或修改时间）→ 有更新
        record.size > 0 && file.size > 0 && record.size != file.size -> GameBundleEntry.State.CHANGED
        record.modified > 0 && file.modified > 0 &&
            kotlin.math.abs(record.modified - file.modified) > 2_000L -> GameBundleEntry.State.CHANGED
        // 提取管线升级：内容仍然可用，只是建议重导以获得更好的产物。
        // 用 CHANGED 表达「可更新」，但 UI 侧不再把它当成必须导入。
        record.pipelineVersion != GamePipelineVersions.of(kind) -> GameBundleEntry.State.CHANGED
        else -> GameBundleEntry.State.IMPORTED
    }

    /** 解析 `f\t/path\t123\tmtime` */
    /** 一次遍历的产物：候选资源包 + （立绘才需要的）文件名索引 */
    private class ParsedListing(
        val candidates: List<RemoteFile>,
        val nameIndex: Map<String, String>
    )

    /**
     * 解析 `f\t/path\t123\tmtime`。
     *
     * 每一行只 split 一次：候选判定与「文件名索引」在同一个循环里完成
     * （分成两遍会让 1 万行的目录多花近 200ms）。
     */
    private fun parseListing(
        lines: List<String>,
        kind: GameAssetKind,
        needNameIndex: Boolean
    ): ParsedListing {
        val out = ArrayList<RemoteFile>(lines.size / 2)
        val index: MutableMap<String, String> =
            if (needNameIndex) HashMap(lines.size) else HashMap(0)
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0] != "f") return@forEach
            val path = parts[1]
            val name = path.substringAfterLast('/')
            if (needNameIndex) index[name.lowercase()] = path
            val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val modified = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            if (!isCandidate(path, name, size, kind)) return@forEach
            out += RemoteFile(path, name, size, modified)
        }
        return ParsedListing(out, index)
    }

    /**
     * 候选判定。
     *
     * 历史实现按「文件名后缀」硬性分流：`spinepainting` 只认 `_res`、
     * `painting` 只认 `_tex`。但游戏的动态皮肤包**并非全部**带 `_res`
     * （例如 `2b_2` / `a2_2` 与默认皮肤同名的换装包），一刀切会让这些皮肤
     * 在扫描阶段就被丢掉——用户看到的就是「某些动态皮肤根本没出现在列表里」。
     *
     * 现在的策略：`spinepainting` / `char` 都放宽到「骨骼类候选」，
     * 真正的类型由导入时的**包内容探测**（[BundleProbe]：有 .skel + .atlas 即 Spine，
     * 并按 `AssetBundle.m_Name` 前缀区分 char / spinepainting）决定。
     * 仍然按名字排除掉预制体/贴图包，避免列表被几十个无用包刷屏。
     */
    private fun isCandidate(path: String, name: String, size: Long, kind: GameAssetKind): Boolean {
        if (size < MIN_BUNDLE_BYTES) return false
        val lower = name.lowercase()
        if (SKIP_NAME_HINTS.any { lower.contains(it) }) return false
        val ext = lower.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext !in BUNDLE_EXTENSIONS) return false
        val base = lower.substringBeforeLast('.')
        return when (kind) {
            // 静态立绘：像素只在 _tex 包里，且必须是**主**立绘包
            // （`_rw` 人物层 / `_bjN` 背景条 / `_front` 前景层都是残图，
            //  判定集中在 [GameAssetNaming.isPaintingLayer]，资源库扫描共用同一规则）
            GameAssetKind.PAINTING -> base.endsWith("_tex") &&
                !GameAssetNaming.isPaintingLayer(base)
            // 动态皮肤 / SD 小人：两者都是「spine 骨骼包」，
            // 允许 _res / 无后缀两种命名，具体归类交给内容探测
            GameAssetKind.DYNAMIC_SKIN -> !base.endsWith("_tex")
            GameAssetKind.SD_CHAR -> !base.endsWith("_tex") && !base.endsWith("_res")
            GameAssetKind.LIVE2D -> true
            // 过场图目录里就是一堆 bg_N，没有需要排除的预制体/分层包
            GameAssetKind.LOADING_BG -> true
        }
    }

    /** 通过 Shizuku 复制远端文件到本地，返回字节数 */
    private suspend fun copyRemote(
        remotePath: String,
        target: File,
        onProgress: (Float) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val service = bridge.requireService()
        val total = runCatching { service.sizeOf(remotePath) }.getOrDefault(0L)
        target.parentFile?.mkdirs()
        runCatching { target.delete() }
        android.os.ParcelFileDescriptor.open(
            target,
            android.os.ParcelFileDescriptor.MODE_CREATE or
                android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                android.os.ParcelFileDescriptor.MODE_TRUNCATE
        ).use { pfd ->
            coroutineScope {
                val poller = launch {
                    while (isActive) {
                        val len = target.length()
                        if (total > 0) onProgress((len.toFloat() / total).coerceIn(0f, 0.99f))
                        delay(200)
                    }
                }
                try {
                    service.copyTo(remotePath, pfd)
                } finally {
                    poller.cancel()
                }
            }
        }
        val copied = target.length()
        if (copied <= 0) throw IOException("Shizuku 复制失败：$remotePath")
        onProgress(1f)
        copied
    }
}
