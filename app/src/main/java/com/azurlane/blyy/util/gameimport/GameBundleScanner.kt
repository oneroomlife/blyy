package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.azurlane.blyy.shizuku.IBlyyShellService
import com.azurlane.blyy.util.shizuku.ShizukuBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Shizuku 通道状态（UI 直接消费） */
enum class ShellState { UNKNOWN, NOT_RUNNING, NO_PERMISSION, READY }

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
    val error: String? = null
) {
    enum class State { NEW, IMPORTED, CHANGED, FAILED }

    val displayName: String get() = fileName.substringBeforeLast('.')
    val kindLabel: String get() = kind.shortLabel
}

/** 一批导入的结果汇总 */
data class GameImportBatchResult(
    val success: List<GameBundleImporter.Result> = emptyList(),
    val failed: List<Pair<String, String>> = emptyList(),
    val cancelled: Boolean = false
) {
    val successCount: Int get() = success.size
    val failureCount: Int get() = failed.size
    val isEmpty: Boolean get() = success.isEmpty() && failed.isEmpty() && !cancelled
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

        /** 游戏资源根目录候选（按优先级） */
        private val GAME_ROOTS = listOf(
            "/sdcard/Android/data/com.bilibili.azurlane/files/AssetBundles",
            "/storage/emulated/0/Android/data/com.bilibili.azurlane/files/AssetBundles"
        )

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

        /** SAF 目录导入时的文件数量上限，避免误选根目录导致长时间占用 */
        private const val MAX_TREE_FILES = 400
    }

    // ------------------------------------------------------------------ 通道状态

    fun shellState(): ShellState = when {
        !bridge.isAvailable() -> ShellState.NOT_RUNNING
        !bridge.isPermissionGranted() -> ShellState.NO_PERMISSION
        else -> ShellState.READY
    }

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

    /** 探测各类型资源目录；不存在则不返回该类型 */
    suspend fun discoverRoots(): Map<GameAssetKind, String> = withContext(Dispatchers.IO) {
        val service = runCatching { bridge.requireService() }.getOrNull() ?: return@withContext emptyMap()
        val found = LinkedHashMap<GameAssetKind, String>()
        for (root in GAME_ROOTS) {
            for (kind in GameAssetKind.ordered) {
                if (found.containsKey(kind)) continue
                val path = "$root/${kind.bundleDir}"
                if (runCatching { service.exists(path) }.getOrDefault(false)) {
                    found[kind] = path
                }
            }
        }
        // 兜底：部分客户端把资源放在 AssetBundles 同级或大小写不同的目录里
        if (found.size < GameAssetKind.ordered.size) {
            val listing = runCatching {
                service.listFiles(GAME_ROOTS.first(), 1, 200)
            }.getOrNull().orEmpty()
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
        val known = index.load().entries.associateBy { it.path }
        val importedIds = library.scanAll().mapValues { (_, list) -> list.map { it.id }.toSet() }

        val entries = ArrayList<GameBundleEntry>()
        for ((kind, root) in roots) {
            val lines = listAllPages(root) ?: emptyList()
            val candidates = parseListing(lines, kind)
            onProgress(entries.size + candidates.size)

            for (file in candidates.sortedBy { it.name.lowercase() }) {
                val record = known[file.path]
                val assetId = record?.takeIf { it.assetKind == kind }?.assetId
                    ?: GameAssetNaming.assetIdOf(file.name, kind)
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
                    error = if (state == GameBundleEntry.State.FAILED) record?.error else null
                )
            }
        }

        // 清理已不存在的路径记录（只清理本次扫描覆盖的根下的记录）
        runCatching {
            val scannedRoots = roots.values.toList()
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
        try {
            onProgress(0.01f, "从游戏目录复制资源包")
            val copied = copyRemote(entry.path, local) { fraction ->
                onProgress(0.01f + fraction * 0.18f, "复制资源包 ${(fraction * 100).toInt()}%")
            }
            if (copied <= 0L) throw IOException("复制失败：0 字节")
            importer.importLocal(
                bundleFile = local,
                sourcePath = entry.path,
                fileNameHint = entry.fileName,
                kindHint = entry.kind,
                sizeHint = entry.size,
                modifiedHint = entry.modified,
                onProgress = { fraction, message -> onProgress(0.20f + fraction * 0.80f, message) }
            )
        } finally {
            runCatching { local.delete() }
        }
    }

    /** SAF 多选文件导入（用户手动指定 .ys / .unity3d / 无扩展名资源包） */
    suspend fun importUris(
        uris: List<Uri>,
        kindHint: GameAssetKind? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (index: Int, total: Int, fileName: String, fraction: Float, message: String) -> Unit =
            { _, _, _, _, _ -> }
    ): GameImportBatchResult = withContext(Dispatchers.IO) {
        val success = mutableListOf<GameBundleImporter.Result>()
        val failed = mutableListOf<Pair<String, String>>()
        var cancelled = false
        uris.forEachIndexed { position, uri ->
            if (isCancelled()) {
                cancelled = true
                return@forEachIndexed
            }
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "所选文件"
            try {
                val result = importer.importUri(uri, kindHint) { fraction, message ->
                    onProgress(position + 1, uris.size, label, fraction, message)
                }
                success += result
            } catch (t: Throwable) {
                failed += label to (t.message ?: t.javaClass.simpleName)
            }
        }
        GameImportBatchResult(success, failed, cancelled)
    }

    /**
     * SAF 目录导入：把用户选中的整个文件夹当作「已解包的游戏资源目录树」，
     * 逐个资源包导入（`resource/test` 这类本地测试资源直接可用）。
     */
    suspend fun importTree(
        treeUri: Uri,
        isCancelled: () -> Boolean = { false },
        onProgress: (index: Int, total: Int, fileName: String, fraction: Float, message: String) -> Unit =
            { _, _, _, _, _ -> }
    ): GameImportBatchResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val files = mutableListOf<Triple<String, Uri, GameAssetKind?>>()
        runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            collectTreeFiles(resolver, treeUri, rootDocId, "", files, 0)
        }.onFailure { Log.w(TAG, "遍历所选目录失败", it) }

        if (files.isEmpty()) {
            return@withContext GameImportBatchResult(
                failed = listOf("所选目录" to "没有找到可导入的资源包（.ys/.unity3d/bundle）")
            )
        }

        val success = mutableListOf<GameBundleImporter.Result>()
        val failed = mutableListOf<Pair<String, String>>()
        var cancelled = false
        files.forEachIndexed { position, (name, uri, kindHint) ->
            if (isCancelled()) {
                cancelled = true
                return@forEachIndexed
            }
            try {
                val result = importer.importUri(uri, kindHint) { fraction, message ->
                    onProgress(position + 1, files.size, name, fraction, message)
                }
                success += result
            } catch (t: Throwable) {
                failed += name to (t.message ?: t.javaClass.simpleName)
            }
        }
        GameImportBatchResult(success, failed, cancelled)
    }

    // ------------------------------------------------------------------ 内部

    private data class RemoteFile(val path: String, val name: String, val size: Long, val modified: Long)

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
        record.assetId !in importedIds -> GameBundleEntry.State.NEW
        record.pipelineVersion != GamePipelineVersions.of(kind) -> GameBundleEntry.State.CHANGED
        record.size != file.size -> GameBundleEntry.State.CHANGED
        record.modified > 0 && file.modified > 0 &&
            kotlin.math.abs(record.modified - file.modified) > 2_000L -> GameBundleEntry.State.CHANGED
        else -> GameBundleEntry.State.IMPORTED
    }

    /** 解析 `f\t/path\t123\tmtime` */
    private fun parseListing(lines: List<String>, kind: GameAssetKind): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0] != "f") return@forEach
            val path = parts[1]
            val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val modified = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            val name = path.substringAfterLast('/')
            if (!isCandidate(path, name, size, kind)) return@forEach
            out += RemoteFile(path, name, size, modified)
        }
        return out
    }

    /**
     * 候选判定（按类型收紧，避免把几十个预制体/配置包都列出来）：
     * - `painting` 只认 `_tex`（不带后缀的是界面预制体，没有像素）
     * - `spinepainting` 只认 `_res`（不带后缀的是预制体，骨骼/贴图在 _res 里）
     * - `char` / `live2d` 目录本身就是资源
     */
    private fun isCandidate(path: String, name: String, size: Long, kind: GameAssetKind): Boolean {
        if (size < MIN_BUNDLE_BYTES) return false
        val lower = name.lowercase()
        if (SKIP_NAME_HINTS.any { lower.contains(it) }) return false
        val ext = lower.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext !in BUNDLE_EXTENSIONS) return false
        val base = lower.substringBeforeLast('.')
        return when (kind) {
            GameAssetKind.PAINTING -> base.endsWith("_tex")
            GameAssetKind.DYNAMIC_SKIN -> base.endsWith("_res")
            GameAssetKind.SD_CHAR -> !base.endsWith("_tex") && !base.endsWith("_res")
            GameAssetKind.LIVE2D -> true
        }
    }

    /** SAF 目录树遍历，收集像资源包的文件（按大小 + 扩展名粗筛） */
    private fun collectTreeFiles(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        docId: String,
        prefix: String,
        out: MutableList<Triple<String, Uri, GameAssetKind?>>,
        depth: Int
    ) {
        if (out.size >= MAX_TREE_FILES || depth > 4) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext() && out.size < MAX_TREE_FILES) {
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: continue
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectTreeFiles(resolver, treeUri, childId, "$prefix$name/", out, depth + 1)
                    continue
                }
                val lower = name.lowercase()
                val ext = lower.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext !in BUNDLE_EXTENSIONS) continue
                if (size in 1 until MIN_BUNDLE_BYTES) continue
                val kind = GameAssetKind.fromBundlePath("$prefix$name")?.let { pathKind ->
                    // 目录名只是提示：_tex/_res 归属由真实内容判定，这里仅在名字能对上时才收紧
                    when {
                        pathKind == GameAssetKind.PAINTING && !lower.contains("_tex") -> null
                        pathKind == GameAssetKind.DYNAMIC_SKIN && !lower.contains("_res") -> null
                        else -> pathKind
                    }
                }
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                out += Triple("$prefix$name", fileUri, kind)
            }
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
