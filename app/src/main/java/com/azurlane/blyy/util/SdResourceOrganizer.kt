package com.azurlane.blyy.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * SD 小人资源整理结果。
 */
data class OrganizeResult(
    val shipCount: Int,
    val skinCount: Int,
    val copiedCount: Int,
    val skippedCount: Int,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = errorMessage == null
}

/**
 * 整理进度阶段。
 */
enum class OrganizePhase {
    /** 扫描源目录中 */
    SCANNING,
    /** 整理复制中 */
    ORGANIZING,
    /** 写入清单 */
    FINALIZING,
    /** 已完成 */
    COMPLETED,
    /** 失败 */
    ERROR
}

/**
 * 整理进度回调数据。
 *
 * @param phase 当前阶段
 * @param current 已处理数量
 * @param total 总数量（扫描阶段为 0 表示未知）
 * @param message 人类可读的进度描述
 * @param shipCount 已发现的舰娘数（扫描阶段递增）
 * @param skinCount 已发现的皮肤数（扫描阶段递增）
 */
data class OrganizeProgress(
    val phase: OrganizePhase,
    val current: Int = 0,
    val total: Int = 0,
    val message: String,
    val shipCount: Int = 0,
    val skinCount: Int = 0
)

/**
 * SD 小人资源整理器（Kotlin 端口自 scripts/organize_sd.py）。
 *
 * 将用户从 SAF 选择的源目录（含散落的 .skel/.atlas/.png 三件套）按
 * 舰娘拼音主名 → 皮肤 二级分类整理到应用专属外部存储：
 *
 * ```
 * <appExternal>/blhx_sd/
 * ├── boge/                       # 舰娘拼音主名（统一小写）
 * │   ├── default/
 * │   │   ├── boge.skel
 * │   │   ├── boge.atlas
 * │   │   └── boge.png
 * │   ├── gai/
 * │   │   ├── boge_g.skel
 * │   │   ├── boge_g.atlas        # 无独立 atlas 时从 default 复制并改写首行
 * │   │   └── boge_g.png
 * │   └── skin2/
 * │       └── ...
 * └── manifest.json
 * ```
 *
 * 整理后 [LocalSdResolver] 即可在免权限的前提下读取资源。
 *
 * 关键规则：
 * - 每个皮肤由一个 .skel 文件定义；无 .skel 的孤儿 atlas/png 合并到 default
 * - 皮肤缺独立 .atlas 时，从 default 复制 atlas 并改写首行引用皮肤专属 .png
 * - 跳过非舰娘资源（loader / redcar）
 * - 默认跳过 _L/_R 半身立绘
 */
object SdResourceOrganizer {

    private const val TAG = "SdResourceOrganizer"

    /** SD 资源根目录名（与 [LocalSdResolver.SD_ROOT_DIR] 一致） */
    private const val SD_ROOT_DIR = "blhx_sd"

    /** manifest.json 文件名 */
    private const val MANIFEST_FILE = "manifest.json"

    /** 跳过的非舰娘资源（工具图标等），统一小写比较 */
    private val SKIP_NAMES = setOf("loader", "redcar")

    /** 皮肤后缀 → 分类名映射 */
    private val SKIN_SUFFIX_MAP = mapOf(
        "g" to "gai",        // 改造
        "h" to "huan",       // 换装
        "hx" to "huangxiang",// 幻象
        "doa" to "doa",      // 死或生联动
        "y" to "teyao",      // 特别计划
        "l" to "left",       // 左半身（默认跳过）
        "r" to "right"       // 右半身（默认跳过）
    )

    // 皮肤后缀正则：匹配 _数字 / _g / _h / _hx / _L / _R / _doa / _y
    private val SKIN_SUFFIX_RE = Regex("^(.+?)(?:_([0-9]+|g|h|hx|L|R|doa|y))?$", RegexOption.IGNORE_CASE)

    // 通用舰种 SD 前缀（srBB/srCA/srCL/srCV/srDD/srSS），数字直接接在后面无下划线
    // group(1) = "srBB" 等舰种前缀；group(2) = 数字 或 "_R" 或 null
    private val SR_PREFIX_RE = Regex("^(sr(?:BB|CA|CL|CV|DD|SS))([0-9]+|_R)?$", RegexOption.IGNORE_CASE)

    /**
     * 单个皮肤资源集合。
     *
     * @property skelUri .skel 文件 URI（皮肤由 .skel 定义，None 表示孤儿文件）
     * @property skelName .skel 文件名（用于决定输出 assetName）
     * @property atlasUri .atlas 文件 URI
     * @property atlasName .atlas 文件名
     * @property pngUri .png 文件 URI
     * @property pngName .png 文件名
     * @property extraPngs 孤儿贴图（保留但不被主 atlas 引用）
     */
    private data class SkinFiles(
        var skelUri: Uri? = null,
        var skelName: String? = null,
        var atlasUri: Uri? = null,
        var atlasName: String? = null,
        var pngUri: Uri? = null,
        var pngName: String? = null,
        val extraPngs: MutableList<Pair<Uri, String>> = mutableListOf()
    ) {
        fun isUsable(): Boolean = skelUri != null
    }

    /**
     * 执行完整整理流程。
     *
     * @param context 任意 Context
     * @param sourceTreeUri 用户通过 SAF ACTION_OPEN_DOCUMENT_TREE 选择的源目录 URI
     * @param includeLeftRight 是否包含 _L/_R 半身立绘（默认 false）
     * @param onProgress 进度回调（在 Dispatchers.Main 上调用）
     */
    suspend fun organize(
        context: Context,
        sourceTreeUri: Uri,
        includeLeftRight: Boolean = false,
        onProgress: (OrganizeProgress) -> Unit
    ): OrganizeResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "organize: 开始整理 sourceTreeUri=$sourceTreeUri, includeLR=$includeLeftRight")

        val resolver = context.contentResolver
        // 验证 tree URI 可访问（不依赖 DocumentFile，直接查询 Document 列）
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(sourceTreeUri)
        } catch (e: Exception) {
            onProgress(OrganizeProgress(OrganizePhase.ERROR, message = "源目录无效"))
            return@withContext OrganizeResult(0, 0, 0, 0, "源目录无效或不可访问")
        }
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, treeDocId)
        val isDir = try {
            resolver.query(
                treeUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                c.moveToFirst() && c.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            } == true
        } catch (e: Exception) {
            false
        }
        if (!isDir) {
            onProgress(OrganizeProgress(OrganizePhase.ERROR, message = "源目录无效"))
            return@withContext OrganizeResult(0, 0, 0, 0, "源目录无效或不可访问")
        }

        // 1. 扫描源目录（含 TextAsset/Texture2D 子目录，向后兼容旧脚本结构）
        onProgress(OrganizeProgress(OrganizePhase.SCANNING, message = "正在扫描源目录…"))
        val groups = try {
            scanSource(resolver, sourceTreeUri, treeUri, includeLeftRight) { scanned, ships, skins ->
                onProgress(OrganizeProgress(
                    OrganizePhase.SCANNING,
                    current = scanned,
                    message = "已扫描 $scanned 个文件，发现 $ships 个舰娘 $skins 个皮肤",
                    shipCount = ships,
                    skinCount = skins
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "organize: 扫描失败", e)
            onProgress(OrganizeProgress(OrganizePhase.ERROR, message = "扫描失败：${e.message}"))
            return@withContext OrganizeResult(0, 0, 0, 0, "扫描失败：${e.message}")
        }

        if (groups.isEmpty()) {
            onProgress(OrganizeProgress(OrganizePhase.ERROR, message = "未在源目录中发现任何 SD 资源（.skel 文件）"))
            return@withContext OrganizeResult(0, 0, 0, 0, "未在源目录中发现任何 SD 资源（.skel 文件）")
        }

        val totalSkins = groups.values.sumOf { skins -> skins.count { it.value.isUsable() } }
        Log.i(TAG, "organize: 扫描完成，${groups.size} 个舰娘，$totalSkins 个皮肤")

        // 2. 准备目标目录（应用专属外部存储，免权限）
        val outDir = File(context.getExternalFilesDir(null), SD_ROOT_DIR)
        if (!outDir.exists() && !outDir.mkdirs()) {
            onProgress(OrganizeProgress(OrganizePhase.ERROR, message = "无法创建输出目录"))
            return@withContext OrganizeResult(0, 0, 0, 0, "无法创建输出目录：$outDir")
        }

        // 3. 整理复制（按 ship/skin 二级结构写入）
        val stats = try {
            copyGroups(context, groups, outDir, totalSkins) { copied, skipped, current, total ->
                onProgress(OrganizeProgress(
                    OrganizePhase.ORGANIZING,
                    current = current,
                    total = total,
                    message = "整理中 $current/$total · 已复制 $copied 跳过 $skipped",
                    shipCount = groups.size,
                    skinCount = totalSkins
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "organize: 复制失败", e)
            onProgress(OrganizeProgress(OrganizePhase.ERROR, message = "复制失败：${e.message}"))
            return@withContext OrganizeResult(0, 0, 0, 0, "复制失败：${e.message}")
        }

        // 4. 写入 manifest.json（供 App 端调试与未来扩展使用）
        onProgress(OrganizeProgress(
            OrganizePhase.FINALIZING,
            message = "正在写入资源清单…",
            shipCount = groups.size,
            skinCount = totalSkins
        ))
        try {
            writeManifest(groups, outDir)
        } catch (e: Exception) {
            Log.w(TAG, "organize: 写入 manifest 失败（不影响整理结果）", e)
        }

        // 5. 通知 LocalSdResolver 重建索引
        LocalSdResolver.refreshIndex()

        val result = OrganizeResult(
            shipCount = groups.size,
            skinCount = totalSkins,
            copiedCount = stats.first,
            skippedCount = stats.second
        )
        Log.i(TAG, "organize: 完成 $result")
        onProgress(OrganizeProgress(
            OrganizePhase.COMPLETED,
            message = "整理完成：${result.shipCount} 个舰娘，${result.skinCount} 个皮肤",
            shipCount = result.shipCount,
            skinCount = result.skinCount
        ))
        result
    }

    /**
     * 扫描源目录，按 (舰娘, 皮肤) 二级分组。
     *
     * 兼容两种源结构：
     * 1. organize_sd.py 期望的结构：TextAsset/ 和 Texture2D/ 子目录
     * 2. 直接平铺：源目录下直接是 .skel/.atlas/.png 文件
     *
     * 自动遍历源目录及其所有子目录（最多 2 层）。
     * 不依赖 DocumentFile，直接用 ContentResolver + DocumentsContract 查询。
     */
    private suspend fun scanSource(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        rootDocUri: Uri,
        includeLeftRight: Boolean,
        onProgress: (scanned: Int, ships: Int, skins: Int) -> Unit
    ): MutableMap<String, MutableMap<String, SkinFiles>> {
        // 收集所有文件（递归一层子目录，兼容 TextAsset/Texture2D 结构）
        val allFiles = mutableListOf<Pair<Uri, String>>()
        collectFiles(resolver, treeUri, rootDocUri, allFiles, maxDepth = 2)

        val ships: MutableMap<String, MutableMap<String, SkinFiles>> = mutableMapOf()
        var scanned = 0

        // Pass 1: 注册皮肤（每个 .skel 定义一个皮肤）
        for ((uri, fileName) in allFiles) {
            coroutineContext.ensureActive()
            scanned++

            val ext = fileExt(fileName)
            if (ext != "skel") {
                continue
            }
            val base = extractBaseName(fileName)
            if (base.isEmpty() || base.lowercase() in SKIP_NAMES) continue
            val skin = classifySkin(fileName)
            if (skin in setOf("left", "right") && !includeLeftRight) continue

            val skinMap = ships.getOrPut(base) { mutableMapOf() }
            val files = skinMap.getOrPut(skin) { SkinFiles() }
            files.skelUri = uri
            files.skelName = fileName

            onProgress(scanned, ships.size, ships.values.sumOf { m -> m.count { it.value.isUsable() } })
        }

        // Pass 2: 分配 .atlas 和 .png 到皮肤
        for ((uri, fileName) in allFiles) {
            coroutineContext.ensureActive()
            val ext = fileExt(fileName)
            if (ext != "atlas" && ext != "png") continue
            val base = extractBaseName(fileName)
            if (base.isEmpty() || base.lowercase() in SKIP_NAMES) continue
            val skin = classifySkin(fileName)
            if (skin in setOf("left", "right") && !includeLeftRight) continue

            val skinMap = ships[base] ?: continue
            val files = skinMap[skin]
            if (files != null && files.skelUri != null) {
                // 该皮肤已有 .skel：直接分配
                if (ext == "atlas" && files.atlasUri == null) {
                    files.atlasUri = uri
                    files.atlasName = fileName
                } else if (ext == "png") {
                    if (files.pngUri == null) {
                        files.pngUri = uri
                        files.pngName = fileName
                    } else {
                        files.extraPngs.add(Pair(uri, fileName))
                    }
                }
            } else {
                // 孤儿文件：归入 default
                val default = skinMap.getOrPut("default") { SkinFiles() }
                if (ext == "atlas" && default.atlasUri == null) {
                    default.atlasUri = uri
                    default.atlasName = fileName
                } else if (ext == "png") {
                    if (default.pngUri == null) {
                        default.pngUri = uri
                        default.pngName = fileName
                    } else {
                        default.extraPngs.add(Pair(uri, fileName))
                    }
                }
            }
        }

        // 清理：移除没有 .skel 的皮肤（将其文件合并到 default）
        val iter = ships.entries.iterator()
        while (iter.hasNext()) {
            val (base, skinMap) = iter.next()
            val skinIter = skinMap.entries.iterator()
            while (skinIter.hasNext()) {
                val (skinName, files) = skinIter.next()
                if (files.skelUri == null && skinName != "default") {
                    val default = skinMap.getOrPut("default") { SkinFiles() }
                    if (files.atlasUri != null && default.atlasUri == null) {
                        default.atlasUri = files.atlasUri
                        default.atlasName = files.atlasName
                    }
                    if (files.pngUri != null) {
                        if (default.pngUri == null) {
                            default.pngUri = files.pngUri
                            default.pngName = files.pngName
                        } else {
                            default.extraPngs.add(Pair(files.pngUri!!, files.pngName!!))
                        }
                    }
                    skinIter.remove()
                }
            }
            // 移除完全空的舰娘（无任何 .skel）
            if (skinMap.values.none { it.skelUri != null }) {
                iter.remove()
            }
        }

        return ships
    }

    /**
     * 递归收集目录下的所有文件（用 ContentResolver 查询 DocumentsContract，限制最大深度）。
     */
    private fun collectFiles(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        docUri: Uri,
        out: MutableList<Pair<Uri, String>>,
        maxDepth: Int
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(docUri))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "collectFiles: 无法查询子文档 $childrenUri", e)
            return
        } ?: return

        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (maxDepth > 0) {
                        collectFiles(resolver, treeUri, childUri, out, maxDepth - 1)
                    }
                } else {
                    out.add(Pair(childUri, name))
                }
            }
        }
    }

    /**
     * 复制分组文件到 <ship>/<skin>/ 二级子目录结构。
     *
     * Atlas 共享策略：
     * - 皮肤有独立 atlas → 原样复制
     * - 皮肤无 atlas 但 default 有 → 复制 default atlas 并改写首行引用皮肤 png
     * - 皮肤也无 png → 复制 default atlas（不改写）+ default png
     *
     * @return Pair(copied, skipped)
     */
    private suspend fun copyGroups(
        context: Context,
        groups: Map<String, Map<String, SkinFiles>>,
        outDir: File,
        totalSkins: Int,
        onProgress: (copied: Int, skipped: Int, current: Int, total: Int) -> Unit
    ): Pair<Int, Int> {
        var copied = 0
        var skipped = 0
        var processed = 0
        val resolver = context.contentResolver

        for ((baseName, skins) in groups.entries.sortedBy { it.key }) {
            coroutineContext.ensureActive()
            val defaultFiles = skins["default"]
            val defaultAtlasUri = defaultFiles?.atlasUri
            val defaultAtlasName = defaultFiles?.atlasName
            val defaultPngUri = defaultFiles?.pngUri
            val defaultPngName = defaultFiles?.pngName

            for ((skinName, files) in skins.entries.sortedBy { it.key }) {
                coroutineContext.ensureActive()
                val skelUri = files.skelUri ?: continue
                val skelName = files.skelName ?: continue
                processed++

                val skinDir = File(outDir, "$baseName/$skinName")
                if (!skinDir.exists() && !skinDir.mkdirs()) {
                    Log.w(TAG, "copyGroups: 无法创建目录 $skinDir，跳过")
                    continue
                }

                // skinAssetName = skel 文件名去扩展名（如 boge_g / he_2）
                val skinAssetName = stripExt(skelName)

                // 1. 复制 .skel（始终保留原文件名）
                val dstSkel = File(skinDir, skelName)
                if (copyFile(resolver, skelUri, dstSkel)) {
                    copied++
                } else {
                    skipped++
                }

                // 2. 处理 atlas + png
                val atlasUri = files.atlasUri
                val atlasName = files.atlasName
                val pngUri = files.pngUri
                val pngName = files.pngName

                if (atlasUri != null && atlasName != null) {
                    // 皮肤有独立 atlas：原样复制
                    val dstAtlas = File(skinDir, atlasName)
                    if (copyFile(resolver, atlasUri, dstAtlas)) copied++ else skipped++
                    // 复制皮肤 png
                    if (pngUri != null && pngName != null) {
                        val dstPng = File(skinDir, pngName)
                        if (copyFile(resolver, pngUri, dstPng)) copied++ else skipped++
                    }
                } else if (defaultAtlasUri != null && defaultAtlasName != null) {
                    // 无独立 atlas：从 default 复制并改写
                    val dstAtlas = File(skinDir, "$skinAssetName.atlas")
                    if (pngUri != null && pngName != null) {
                        // 改写 atlas 首行引用皮肤 png
                        adaptAtlasForSkin(resolver, defaultAtlasUri, pngName, dstAtlas)
                        copied++
                        // 复制皮肤 png
                        val dstPng = File(skinDir, pngName)
                        if (copyFile(resolver, pngUri, dstPng)) copied++ else skipped++
                    } else if (defaultPngUri != null && defaultPngName != null) {
                        // 皮肤无 png：复制 default atlas（不改写）+ default png
                        if (copyFile(resolver, defaultAtlasUri, dstAtlas)) copied++ else skipped++
                        val dstPng = File(skinDir, defaultPngName)
                        if (copyFile(resolver, defaultPngUri, dstPng)) copied++ else skipped++
                    }
                } else {
                    // 无 atlas 资源：仅复制 png
                    if (pngUri != null && pngName != null) {
                        val dstPng = File(skinDir, pngName)
                        if (copyFile(resolver, pngUri, dstPng)) copied++ else skipped++
                    }
                }

                // 3. 复制额外 png（孤儿贴图，保留但不被 atlas 引用）
                for ((extraUri, extraName) in files.extraPngs) {
                    coroutineContext.ensureActive()
                    val dstExtra = File(skinDir, extraName)
                    if (copyFile(resolver, extraUri, dstExtra)) copied++ else skipped++
                }

                onProgress(copied, skipped, processed, totalSkins)
            }
        }

        return copied to skipped
    }

    /**
     * 复制单个文件（增量：已存在且同大小则跳过）。返回 true=已复制，false=跳过。
     */
    private fun copyFile(
        resolver: android.content.ContentResolver,
        srcUri: Uri,
        dst: File
    ): Boolean {
        // 增量检查：目标已存在且同大小则跳过
        if (dst.exists()) {
            val srcSize = queryFileSize(resolver, srcUri)
            if (srcSize > 0 && srcSize == dst.length()) {
                return false
            }
        }
        dst.parentFile?.mkdirs()
        resolver.openInputStream(srcUri)?.use { input ->
            dst.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: run {
            Log.w(TAG, "copyFile: 无法打开输入流 $srcUri")
            return false
        }
        return true
    }

    /**
     * 复制 atlas 文件并改写首行引用为皮肤专属 .png。
     *
     * Spine atlas 文件第一行是 png 文件名，TextureAtlas 从 atlas 文件同级目录加载该 png。
     * 改写首行后，皮肤目录中的 `<skin>.png` 即可被正确加载。
     */
    private fun adaptAtlasForSkin(
        resolver: android.content.ContentResolver,
        srcAtlasUri: Uri,
        skinPngName: String,
        dstAtlas: File
    ) {
        dstAtlas.parentFile?.mkdirs()
        resolver.openInputStream(srcAtlasUri)?.use { input ->
            dstAtlas.outputStream().use { output ->
                val reader = input.bufferedReader(Charsets.UTF_8)
                val writer = output.bufferedWriter(Charsets.UTF_8)
                var firstNonEmptyWritten = false
                var line = reader.readLine()
                while (line != null) {
                    if (!firstNonEmptyWritten && line.isNotEmpty()) {
                        // 改写第一个非空行（png 引用行）
                        writer.write(skinPngName)
                        writer.newLine()
                        firstNonEmptyWritten = true
                    } else {
                        writer.write(line)
                        writer.newLine()
                    }
                    line = reader.readLine()
                }
                writer.flush()
            }
        } ?: Log.w(TAG, "adaptAtlasForSkin: 无法打开 atlas $srcAtlasUri")
    }

    /**
     * 通过 ContentResolver 查询文件大小。
     */
    private fun queryFileSize(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Long {
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * 写入 manifest.json（v2，含皮肤子目录信息）。
     */
    private fun writeManifest(groups: Map<String, Map<String, SkinFiles>>, outDir: File) {
        val shipsJson = StringBuilder()
        var skinTotal = 0
        shipsJson.append("{\n")
        val sortedShips = groups.entries.sortedBy { it.key }
        for ((shipIndex, shipEntry) in sortedShips.withIndex()) {
            val baseName = shipEntry.key
            val skins = shipEntry.value
            val sortedSkins = skins.entries.filter { it.value.skelUri != null }.sortedBy { it.key }
            if (sortedSkins.isEmpty()) continue

            shipsJson.append("    \"").append(baseName).append("\": {\"skins\": {\n")
            for ((skinIndex, skinEntry) in sortedSkins.withIndex()) {
                val skinName = skinEntry.key
                val files = skinEntry.value
                skinTotal++
                shipsJson.append("      \"").append(skinName).append("\": {")
                shipsJson.append("\"dir\": \"").append(skinName).append("\", ")
                shipsJson.append("\"skel\": \"").append(files.skelName ?: "").append('"')
                files.atlasName?.let { shipsJson.append(", \"atlas\": \"").append(it).append('"') }
                files.pngName?.let { shipsJson.append(", \"png\": \"").append(it).append('"') }
                if (files.extraPngs.isNotEmpty()) {
                    shipsJson.append(", \"extra_pngs\": [")
                    files.extraPngs.forEachIndexed { i, pair ->
                        if (i > 0) shipsJson.append(", ")
                        shipsJson.append('"').append(pair.second).append('"')
                    }
                    shipsJson.append(']')
                }
                shipsJson.append('}')
                if (skinIndex < sortedSkins.size - 1) shipsJson.append(',')
                shipsJson.append('\n')
            }
            shipsJson.append("    }}")
            if (shipIndex < sortedShips.size - 1) shipsJson.append(',')
            shipsJson.append('\n')
        }
        shipsJson.append("  }")

        val manifest = """{
  "version": 2,
  "generated_at": "${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())}",
  "ship_count": ${sortedShips.size},
  "skin_count": $skinTotal,
  "ships": $shipsJson
}
""".trimIndent()

        File(outDir, MANIFEST_FILE).writeText(manifest, Charsets.UTF_8)
        Log.i(TAG, "writeManifest: ${sortedShips.size} 舰娘, $skinTotal 皮肤 → ${File(outDir, MANIFEST_FILE)}")
    }

    // ============ 文件名解析工具（端口自 organize_sd.py） ============

    /**
     * 从文件名提取舰娘拼音主名（去除扩展名和皮肤后缀，统一小写）。
     *
     * 统一小写是因为 App 端 PinyinHelper.toPinyin() 始终输出小写拼音，
     * 目录名也需小写才能匹配。
     */
    private fun extractBaseName(fileName: String): String {
        val name = stripExt(fileName)
        // 通用舰种前缀：srBB0 / srCA_R 等
        val srMatch = SR_PREFIX_RE.matchEntire(name)
        if (srMatch != null) {
            // group(1) = "srBB" / "srCA" 等
            return srMatch.groupValues[1].lowercase()
        }
        val match = SKIN_SUFFIX_RE.matchEntire(name)
        // group(1) = 主名（如 boge），可能为空字符串（极端情况）
        val base = if (match != null && match.groupValues.size > 1) match.groupValues[1] else name
        return base.lowercase()
    }

    /**
     * 识别皮肤类型，返回 'default' / 'gai' / 'skin2' / 'huan' 等。
     */
    private fun classifySkin(fileName: String): String {
        val name = stripExt(fileName)
        val srMatch = SR_PREFIX_RE.matchEntire(name)
        if (srMatch != null) {
            // group(2) = 数字 或 "_R" 或 null
            val suffix = srMatch.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            if (suffix.isNullOrEmpty()) return "default"
            if (suffix.equals("_R", ignoreCase = true) || suffix.equals("R", ignoreCase = true)) return "mirror"
            return "skin$suffix"
        }
        val match = SKIN_SUFFIX_RE.matchEntire(name) ?: return "default"
        // group(2) = 后缀（如 "g"/"h"/"2"/"_R"）或 null
        val suffix = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.lowercase() ?: return "default"
        if (suffix.isEmpty()) return "default"
        SKIN_SUFFIX_MAP[suffix]?.let { return it }
        if (suffix.all { it.isDigit() }) return "skin$suffix"
        return "default"
    }

    /** 返回扩展名类型: "skel" / "atlas" / "png" / "" */
    private fun fileExt(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".skel") -> "skel"
            lower.endsWith(".atlas") -> "atlas"
            lower.endsWith(".png") -> "png"
            else -> ""
        }
    }

    /** 去除文件扩展名（大小写不敏感） */
    private fun stripExt(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".skel") -> fileName.substring(0, fileName.length - 5)
            lower.endsWith(".atlas") -> fileName.substring(0, fileName.length - 6)
            lower.endsWith(".png") -> fileName.substring(0, fileName.length - 4)
            else -> fileName
        }
    }

    /**
     * 清空应用专属外部存储的 SD 资源目录（用于"重新整理"场景）。
     * 仅删除 <ship>/ 子目录和 manifest.json，不删除 blhx_sd/ 根目录本身。
     */
    fun clearAppSdDir(context: Context): Boolean {
        val outDir = File(context.getExternalFilesDir(null), SD_ROOT_DIR)
        if (!outDir.exists()) return true
        return try {
            outDir.listFiles()?.forEach { it.deleteRecursively() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearAppSdDir: 清空失败", e)
            false
        }
    }
}

/** 让 OutputStream 接收 File.outputStream() 的扩展，避免每次写文件时显式创建 FileOutputStream */
private fun File.outputStream(): OutputStream = java.io.FileOutputStream(this)
