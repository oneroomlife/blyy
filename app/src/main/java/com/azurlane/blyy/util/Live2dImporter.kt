package com.azurlane.blyy.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live2D 模型导入器：把设备上的模型文件夹（SAF 目录树）或 zip 压缩包
 * 归一化拷入 [Live2dLibrary] 根目录。
 *
 * 归一化规则（对用户完全透明，自动处理各种目录形态）：
 * - 递归寻找所有 *.model3.json，以其【所在目录】为一个模型的根
 *   （兼容 resource/live2d/<name>/<name>/ 这类双层嵌套结构）
 * - 模型 id = 模型根目录名；与库中已有 id 重名 → 原位替换（视为重新导入）
 * - 同一次导入中遇到重名模型根（极少见）→ 自动追加 -2/-3 后缀
 * - 仅拷贝模型根下的文件，导入源里的无关文件不进入库
 */
@Singleton
class Live2dImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: Live2dLibrary
) {
    companion object {
        private const val TAG = "Live2dImporter"
        private const val MODEL3_EXT = ".model3.json"
        private const val MAX_WALK_DEPTH = 6
        private const val MAX_ENTRIES = 20000
    }

    enum class Phase { SCANNING, COPYING, EXTRACTING, FINALIZING }

    data class ImportProgress(
        val phase: Phase,
        /** 正在处理的模型目录名 */
        val currentModel: String? = null,
        val copiedFiles: Int = 0,
        val totalFiles: Int = 0,
        /** 已发现的模型数量 */
        val foundModels: Int = 0
    )

    data class ImportResult(
        /** 新增的模型 id */
        val imported: List<String>,
        /** 原位替换的模型 id */
        val updated: List<String>,
        /** 失败明细 "id: 原因" */
        val failed: List<String>,
        /** 是否被用户取消（部分拷贝的目录已回滚） */
        val cancelled: Boolean
    ) {
        val successCount: Int get() = imported.size + updated.size
    }

    private data class SourceFile(
        /** 相对导入根的路径（'/' 分隔），模型文件拷贝时保留此相对结构 */
        val relPath: String,
        /** SAF 模式下的文档 uri；zip 模式为 null（文件已在本地缓存目录） */
        val uri: Uri?,
        /** zip 模式下的本地缓存文件；SAF 模式为 null */
        val localFile: File?
    )

    /** SAF 目录树导入（用户通过系统文件选择器选中一个文件夹） */
    suspend fun importFromTree(
        treeUri: Uri,
        onProgress: (ImportProgress) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): ImportResult = withContext(Dispatchers.IO) {
        val createdTargets = mutableListOf<String>()
        try {
            val resolver = context.contentResolver
            val files = mutableListOf<SourceFile>()
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            walkTree(resolver, treeUri, rootDocId, "", files, 0)

            onProgress(ImportProgress(Phase.COPYING))
            importCollected(
                files = files,
                groupByModelRoot = { relPath -> relPath.substringBeforeLast('/', "") },
                // 用户直接选中模型文件夹本身时（模型根相对路径为空串），
                // 用所选目录名兜底，避免退化成"目录名无效"
                fallbackName = treeDisplayName(treeUri),
                onProgress = onProgress,
                isCancelled = isCancelled,
                createdTargets = createdTargets
            )
        } catch (e: Exception) {
            Log.e(TAG, "文件夹导入失败", e)
            createdTargets.forEach { runCatching { File(library.rootDir(), it).deleteRecursively() } }
            ImportResult(emptyList(), emptyList(), listOf("导入失败: ${e.message ?: "未知错误"}"), false)
        } finally {
            // 持久化授权只服务于「导入期间 Activity 重建后仍可读取目录」；用完即还。
            // 不释放的话每次文件夹导入都会吃掉一个系统持久化授权配额（Android 10 起上限 128 个）
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    /** zip 压缩包导入：流式解包到缓存目录（只解模型文件），再按本地目录归一化入库 */
    suspend fun importFromZip(
        zipUri: Uri,
        onProgress: (ImportProgress) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "live2d_import_${System.currentTimeMillis()}")
        // SAF 输入流只支持顺序读一遍，先落地为缓存临时文件，再用 [ZipFile] 随机访问：
        // - 枚举条目只读 central directory（O(entries)，不解压任何数据）
        //   此前用 ZipInputStream"只列目录"的第一遍实际把整个包解压流读了一遍
        //   （closeEntry 需读取并丢弃条目全部数据才能定位下一目录项），
        //   大压缩包的解压 I/O 与耗时直接翻倍
        // - 解压阶段按条目 getInputStream 精确读取，天然跳过无关条目
        val srcFile = File(context.cacheDir, "live2d_import_src_${System.currentTimeMillis()}.zip")
        val createdTargets = mutableListOf<String>()
        try {
            onProgress(ImportProgress(Phase.EXTRACTING))
            tempDir.mkdirs()
            val resolver = context.contentResolver

            resolver.openInputStream(zipUri)?.use { input ->
                srcFile.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult(
                emptyList(), emptyList(), listOf("无法读取压缩包"), false
            )

            ZipFile(srcFile).use { zip ->
                // zip 条目名先归一化（Windows 打包工具会写反斜杠，也会带 "./" 前缀），
                // 否则"模型根"永远算不出来，整包模型会被判成"目录名无效"
                val fileEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { entry ->
                        Live2dModelLayout.normalizeEntryPath(entry.name)?.let { rel -> entry to rel }
                    }
                    .toList()
                val model3Entries = fileEntries.filter { (_, rel) ->
                    rel.endsWith(MODEL3_EXT, ignoreCase = true)
                }
                if (model3Entries.isEmpty()) {
                    return@withContext ImportResult(
                        emptyList(),
                        emptyList(),
                        listOf("压缩包内未找到 *.model3.json 模型文件（仅支持 Cubism 3/4）"),
                        false
                    )
                }
                val modelRoots = model3Entries.map { (_, rel) -> rel.substringBeforeLast('/', "") }.distinct()

                // 仅解包属于模型根的条目（归属规则与 importCollected 一致：
                // 取最长匹配根，保证根目录下 motions/textures 等子目录一并解出）
                var extracted = 0
                for ((entry, rel) in fileEntries) {
                    if (Live2dModelLayout.ownerRoot(rel, modelRoots) != null) {
                        val out = safeOutputFile(tempDir, rel)
                        if (out != null) {
                            // zip 条目按需逐级建父目录（目录条目在压缩包里可能缺省）
                            out.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { zipIn ->
                                out.outputStream().use { zipIn.copyTo(it) }
                            }
                            extracted++
                            if (extracted % 20 == 0) {
                                onProgress(
                                    ImportProgress(
                                        Phase.EXTRACTING,
                                        copiedFiles = extracted
                                    )
                                )
                                if (isCancelled()) throw InterruptedException("已取消")
                            }
                        }
                    }
                }
            }

            onProgress(ImportProgress(Phase.COPYING))
            val files = mutableListOf<SourceFile>()
            tempDir.walkTopDown().filter { it.isFile }.forEach { f ->
                files += SourceFile(f.relativeTo(tempDir).invariantSeparatorsPath, null, f)
            }
            importCollected(
                files = files,
                groupByModelRoot = { relPath -> relPath.substringBeforeLast('/', "") },
                // 压缩包里模型就在包根（没有外层目录）时，用压缩包文件名兜底命名
                fallbackName = documentDisplayName(zipUri)?.substringBeforeLast('.'),
                onProgress = onProgress,
                isCancelled = isCancelled,
                createdTargets = createdTargets
            )
        } catch (e: InterruptedException) {
            createdTargets.forEach { runCatching { File(library.rootDir(), it).deleteRecursively() } }
            ImportResult(emptyList(), emptyList(), emptyList(), cancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "压缩包导入失败", e)
            createdTargets.forEach { runCatching { File(library.rootDir(), it).deleteRecursively() } }
            // 面向用户隐藏内部缓存路径，只保留可读的原因
            val reason = when (e) {
                is java.io.FileNotFoundException -> "压缩包无法读取或已损坏"
                is java.util.zip.ZipException -> "压缩包格式错误或已损坏"
                else -> e.message?.substringAfterLast('/')?.take(80) ?: "未知错误"
            }
            ImportResult(emptyList(), emptyList(), listOf("导入失败: $reason"), false)
        } finally {
            runCatching { tempDir.deleteRecursively() }
            runCatching { srcFile.delete() }
        }
    }

    // ---------- 通用归一化入库 ----------

    /**
     * 把收集到的文件按"model3.json 所在目录 = 模型根"分组，逐模型拷入库根目录。
     * [groupByModelRoot] 从相对路径给出模型根相对目录（顶层模型为空串）。
     * [fallbackName] 顶层模型（模型根为空串）时的命名兜底：SAF 所选目录名 / zip 文件名。
     */
    private suspend fun importCollected(
        files: List<SourceFile>,
        groupByModelRoot: (String) -> String,
        fallbackName: String? = null,
        onProgress: (ImportProgress) -> Unit,
        isCancelled: () -> Boolean,
        createdTargets: MutableList<String>
    ): ImportResult {
        val model3Files = files.filter { it.relPath.endsWith(MODEL3_EXT, ignoreCase = true) }
        if (model3Files.isEmpty()) {
            return ImportResult(emptyList(), emptyList(), listOf("未找到任何 *.model3.json 模型文件"), false)
        }

        // 模型根相对目录 → 该模型的全部文件（含全部子目录）
        // 归属规则：文件属于"其路径所匹配的最长模型根"（防止嵌套根互相抢文件）
        data class ModelGroup(val rootRel: String, val files: List<SourceFile>)
        val rootRels = model3Files.map { groupByModelRoot(it.relPath) }.distinct()
        val groups = rootRels.map { rootRel ->
            val owned = files.filter { f ->
                Live2dModelLayout.ownerRoot(f.relPath, rootRels) == rootRel
            }
            ModelGroup(rootRel, owned)
        }

        // 同一次导入内的重名模型根（不同层级出现同名目录）→ 记录使用过的 id
        val usedIds = mutableSetOf<String>()
        val imported = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var copiedTotal = 0
        val totalFiles = groups.sumOf { it.files.size }

        for (group in groups) {
            if (isCancelled()) {
                createdTargets.forEach { runCatching { File(library.rootDir(), it).deleteRecursively() } }
                return ImportResult(emptyList(), emptyList(), emptyList(), cancelled = true)
            }
            // 命名优先级：模型根目录名 → model3.json 文件名 → 导入源名称。
            // 旧实现只认模型根目录名，用户"直接选中模型文件夹本身"（或 zip 内模型就在包根）
            // 时根相对路径是空串 → 判成「目录名无效」，整模型被丢弃 → 库里当然看不到。
            val primaryModel3 = group.files
                .filter { it.relPath.endsWith(MODEL3_EXT, ignoreCase = true) }
                .minByOrNull { f -> f.relPath.count { it == '/' } }
            val folderName = Live2dModelLayout.modelIdFor(
                rootRel = group.rootRel,
                model3Rel = primaryModel3?.relPath.orEmpty(),
                fallback = fallbackName
            )
            if (folderName.isEmpty()) {
                failed += "${group.rootRel.ifEmpty { "(根目录)" }}: 无法确定模型名，请把模型放进独立文件夹后重试"
                continue
            }
            // 重名规则：本次导入已占用 → 加后缀；库中已存在 → 原位替换；否则新增
            var targetId = folderName
            if (targetId in usedIds) {
                var seq = 2
                while ("$folderName-$seq" in usedIds) seq++
                targetId = "$folderName-$seq"
                imported += targetId
            } else if (File(library.rootDir(), targetId).exists()) {
                updated += targetId
            } else {
                imported += targetId
            }
            usedIds += targetId

            val targetDir = File(library.rootDir(), targetId)
            try {
                // 替换场景先清空旧目录，保证库内容与导入源完全一致
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()
                createdTargets += targetId

                for (f in group.files) {
                    if (isCancelled()) throw InterruptedException("已取消")
                    val relUnderModel = f.relPath.removePrefix(
                        if (group.rootRel.isEmpty()) "" else "${group.rootRel}/"
                    )
                    val out = File(targetDir, relUnderModel)
                    // canonicalPath 前缀比较必须带分隔符，否则同级兄弟目录
                    // （如 targetDirX）会误判通过（与 Live2dWebViewClient 的校验一致）
                    if (!out.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        Log.w(TAG, "跳过越界路径: ${f.relPath}")
                        continue
                    }
                    out.parentFile?.mkdirs()
                    copySourceToFile(f, out)
                    copiedTotal++
                    onProgress(
                        ImportProgress(
                            Phase.COPYING,
                            currentModel = targetId,
                            copiedFiles = copiedTotal,
                            totalFiles = totalFiles,
                            foundModels = groups.size
                        )
                    )
                }

                // 落盘后合格化校验：
                // 1) model3.json 里的引用（Moc/Textures/Motions…）若与磁盘真实文件名不一致
                //    （Windows 打包的大小写差异 / 反斜杠 / 贴图换了层级）就地改写为真实路径，
                //    否则扫描会把它判成"model3.json 损坏"→ 模型永远不显示
                // 2) 缺 moc3 或全部贴图 → 当场报失败，而不是静默入库后看不见
                val outcome = runCatching { Live2dModelLocalizer.localize(targetDir) }
                    .getOrElse { Live2dModelLocalizer.Outcome(true) }
                if (!outcome.ok) {
                    val reason = outcome.reason ?: "模型文件不完整"
                    Log.w(TAG, "模型不可渲染: $targetId → $reason")
                    failed += "$targetId: $reason"
                    runCatching { targetDir.deleteRecursively() }
                    imported -= targetId
                    updated -= targetId
                    createdTargets -= targetId
                    continue
                }
                if (outcome.rewrittenRefs > 0) {
                    Log.i(TAG, "已修正 $targetId 的 ${outcome.rewrittenRefs} 处模型引用路径")
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "拷贝模型失败: ${group.rootRel}", e)
                failed += "$folderName: ${e.message ?: "拷贝失败"}"
                runCatching { targetDir.deleteRecursively() }
                imported -= targetId
                updated -= targetId
                createdTargets -= targetId
            }
        }

        onProgress(ImportProgress(Phase.FINALIZING, copiedFiles = copiedTotal, totalFiles = totalFiles, foundModels = groups.size))
        return ImportResult(imported, updated, failed, cancelled = false)
    }

    /**
     * 所选 SAF 目录树的显示名（用户"直接选中模型文件夹本身"时用它当模型名兜底）。
     * 取不到就返回 null，由 modelIdFor 继续向下兜底。
     */
    private fun treeDisplayName(treeUri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        documentDisplayName(docUri)
    }.getOrNull()

    /** 单个 document uri 的显示名（zip 文件名兜底用） */
    private fun documentDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun copySourceToFile(src: SourceFile, out: File) {
        if (src.localFile != null) {
            src.localFile.copyTo(out, overwrite = true)
        } else if (src.uri != null) {
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法打开源文件: ${src.relPath}")
        } else {
            throw IllegalStateException("空来源: ${src.relPath}")
        }
    }

    /** 递归遍历 SAF 目录树，收集全部文件（含相对路径） */
    private fun walkTree(
        resolver: ContentResolver,
        treeUri: Uri,
        docId: String,
        prefix: String,
        out: MutableList<SourceFile>,
        depth: Int
    ) {
        if (out.size >= MAX_ENTRIES) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext() && out.size < MAX_ENTRIES) {
                val childDocId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: continue
                val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < MAX_WALK_DEPTH) {
                        walkTree(resolver, treeUri, childDocId, relPath, out, depth + 1)
                    }
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    out += SourceFile(relPath, fileUri, null)
                }
            }
        } ?: Log.w(TAG, "无法查询目录子项: $prefix")
    }

    /** zip 条目名 → 缓存目录内文件（防 zip-slip 路径穿越） */
    private fun safeOutputFile(baseDir: File, entryName: String): File? {
        val sanitized = entryName.replace('\\', '/')
        val out = File(baseDir, sanitized)
        // canonicalPath 前缀比较必须带分隔符，否则同级兄弟目录会误判通过
        return if (out.canonicalPath.startsWith(baseDir.canonicalPath + File.separator)) out else null
    }
}
