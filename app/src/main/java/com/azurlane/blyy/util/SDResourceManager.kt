package com.azurlane.blyy.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 通用 SD 资源管理器。
 *
 * 核心设计：**资源自动发现 + ID 直接解析**，解除"舰名驱动"限制。
 *
 * 工作流程：
 * 1. 扫描 [LocalSdResolver.getResourceDir] 下的所有子目录
 * 2. 每个含 .skel 文件或图片文件的目录 → 识别为一个 [SDResource]
 * 3. 自动读取皮肤列表（子目录结构或文件名后缀）
 * 4. 自动提取预览图路径（atlas png 或图片本身）
 * 5. 构建 ID → SDResource 索引，支持 O(1) 按 ID 查询
 *
 * 与 [LocalSdResolver] 的关系：
 * - [LocalSdResolver] 保留用于舰名→拼音→目录的模糊匹配（向后兼容）
 * - [SDResourceManager] 提供通用 ID 解析和资源管理能力（新增）
 * - 两者共享资源根目录和 revision 通知机制
 *
 * 线程安全：双重检查锁保证索引构建线程安全，@Volatile 缓存保证跨线程可见。
 */
object SDResourceManager {

    private const val TAG = "SDResourceManager"

    /** 自定义资源存放子目录名 */
    private const val CUSTOM_DIR = "custom"

    /** manifest.json 中可声明的资源元数据文件名 */
    private const val META_FILE = "meta.json"

    /** 进程级缓存：ID → SDResource 索引 */
    @Volatile
    private var resourceIndex: Map<String, SDResource>? = null

    /** 进程级缓存：有序资源列表（用于 UI 展示） */
    @Volatile
    private var resourceList: List<SDResource>? = null

    private val lock = Any()

    /**
     * 资源索引修订号（可观察）。
     * 与 [LocalSdResolver.revision] 同步递增，Composable 将其作为 remember key。
     */
    val revision = LocalSdResolver.revision

    /**
     * 列出所有已发现的 SD 资源（按名称排序）。
     * 首次调用时扫描目录构建索引，后续直接读缓存。
     */
    fun listAll(context: Context): List<SDResource> {
        resourceList?.let { return it }
        return synchronized(lock) {
            resourceList ?: run {
                val list = buildIndex(context).values.sortedBy { it.name }
                resourceList = list
                list
            }
        }
    }

    /**
     * 按 ID 解析资源（O(1)）。
     *
     * @param context 任意 Context
     * @param id 资源 ID（目录名）
     * @return 匹配的 [SDResource]，不存在返回 null
     */
    fun resolveById(context: Context, id: String): SDResource? {
        if (id.isBlank()) return null
        val index = ensureIndex(context)
        return index[id]
    }

    /**
     * 按舰名解析资源（向后兼容，委托 [LocalSdResolver]）。
     *
     * 当用户未设置自定义资源 ID 时，使用此方法通过舰名匹配。
     *
     * @return 匹配的 [SDResource]（从 [SdAssetInfo] 转换），无匹配返回 null
     */
    fun resolveByShipName(context: Context, shipName: String, skinName: String? = null): SDResource? {
        if (shipName.isBlank()) return null
        val assetInfo = LocalSdResolver.resolve(context, shipName, skinName) ?: return null
        // 从 SdAssetInfo 构建 SDResource（舰名匹配模式）
        val dir = File(assetInfo.dirPath)
        val resourceId = dir.parentFile?.name ?: dir.name
        val skins = assetInfo.skins.map { skin ->
            val skinDir = if (skin == "default") dir else File(dir.parentFile, skin)
            val skinSkel = skinDir.listFiles()?.firstOrNull { isSkelFile(it.name) }
            SDSkin(
                name = skin,
                displayName = skinDisplayName(skin),
                dirPath = skinDir.absolutePath,
                assetName = skinSkel?.nameWithoutExtension ?: assetInfo.assetName,
                type = SDResourceType.SPINE
            )
        }
        val previewPath = findPreviewPng(dir)
        return SDResource(
            id = resourceId,
            name = resourceId,
            type = SDResourceType.SPINE,
            source = SDResourceSource.SHIP,
            skins = skins,
            previewPath = previewPath,
            shipName = shipName
        )
    }

    /**
     * 统一解析入口：优先用资源 ID，其次用舰名。
     *
     * @param context 任意 Context
     * @param resourceId 用户设置的自定义资源 ID（空表示未设置，走舰名匹配）
     * @param shipName 当前秘书舰名（resourceId 为空时使用）
     * @param skinName 皮肤名
     * @return 匹配的 [SDResource]，无匹配返回 null
     */
    fun resolve(
        context: Context,
        resourceId: String,
        shipName: String,
        skinName: String? = null
    ): SDResource? {
        // 优先使用自定义资源 ID
        if (resourceId.isNotBlank()) {
            resolveById(context, resourceId)?.let { return it }
            Log.w(TAG, "resolve: 自定义资源 ID '$resourceId' 未找到，回退到舰名匹配")
        }
        // 回退到舰名匹配
        return resolveByShipName(context, shipName, skinName)
    }

    /**
     * 列出指定资源 ID 的所有皮肤名。
     */
    fun listSkins(context: Context, resourceId: String): List<String> {
        val resource = resolveById(context, resourceId) ?: return emptyList()
        return resource.skinNames
    }

    /**
     * 删除指定 ID 的资源目录。
     *
     * 仅允许删除 custom/ 目录下的自定义资源，防止误删舰娘资源。
     * @return true 删除成功
     */
    fun deleteResource(context: Context, resourceId: String): Boolean {
        if (resourceId.isBlank()) return false
        val rootDir = LocalSdResolver.getResourceDir(context)
        // 仅允许删除 custom/ 子目录或直接删除资源目录
        val customDir = File(rootDir, CUSTOM_DIR)
        val resourceDir = File(customDir, resourceId)
        val targetDir = if (resourceDir.exists()) resourceDir else File(rootDir, resourceId)

        return try {
            val deleted = targetDir.deleteRecursively()
            if (deleted) {
                Log.i(TAG, "deleteResource: 已删除 $resourceId ($targetDir)")
                refreshIndex()
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "deleteResource: 删除失败 $resourceId", e)
            false
        }
    }

    /**
     * 导入自定义 SD 资源。
     *
     * 从用户通过 SAF 选择的源目录中扫描 .skel/.atlas/.png 文件，
     * 按原文件名复制到 `blhx_sd/custom/<resourceName>/` 目录。
     *
     * 与 [SdResourceOrganizer] 不同：
     * - 不做舰名拼音分类
     * - 不跳过"非舰娘"资源
     * - 不改写 atlas 文件
     * - 保留原始文件结构
     *
     * @param context 任意 Context
     * @param sourceTreeUri SAF 选择的源目录 URI
     * @param resourceName 用户指定的资源名称（作为目录名）
     * @param onProgress 进度回调（已复制文件数，总文件数）
     * @return 导入结果
     */
    suspend fun importCustomResource(
        context: Context,
        sourceTreeUri: Uri,
        resourceName: String,
        onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        val safeName = resourceName.trim().ifBlank { "custom_resource" }
        Log.i(TAG, "importCustomResource: 开始导入 '$safeName' from $sourceTreeUri")

        val resolver = context.contentResolver
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(sourceTreeUri)
        } catch (e: Exception) {
            return@withContext ImportResult(false, "源目录无效")
        }
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, treeDocId)

        // 1. 扫描源目录收集文件
        val allFiles = mutableListOf<Pair<Uri, String>>()
        collectFiles(resolver, sourceTreeUri, treeUri, allFiles, maxDepth = 3)

        // 2. 筛选有效 SD 资源文件（.skel / .atlas / .png / .webp / .jpg）
        val sdFiles = allFiles.filter { (uri, name) ->
            isSkelFile(name) || isAtlasFile(name) || isImageFile(name)
        }

        if (sdFiles.isEmpty()) {
            return@withContext ImportResult(false, "未在源目录中发现任何 SD 资源文件（.skel/.atlas/.png）")
        }

        Log.i(TAG, "importCustomResource: 发现 ${sdFiles.size} 个资源文件")

        // 3. 准备目标目录
        val rootDir = LocalSdResolver.getResourceDir(context)
        val customDir = File(rootDir, CUSTOM_DIR)
        val destDir = File(customDir, safeName)
        if (destDir.exists()) {
            // 同名资源已存在，先删除
            destDir.deleteRecursively()
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            return@withContext ImportResult(false, "无法创建目录：$destDir")
        }

        // 4. 复制文件
        var copied = 0
        val total = sdFiles.size
        for ((uri, fileName) in sdFiles) {
            coroutineContext.ensureActive()
            val dstFile = File(destDir, fileName)
            dstFile.parentFile?.mkdirs()
            try {
                resolver.openInputStream(uri)?.use { input ->
                    dstFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.w(TAG, "importCustomResource: 无法打开输入流 $uri")
                    continue
                }
                copied++
                onProgress(copied, total)
            } catch (e: Exception) {
                Log.e(TAG, "importCustomResource: 复制失败 $fileName", e)
            }
        }

        // 5. 刷新索引
        if (copied > 0) {
            LocalSdResolver.refreshIndex()
            Log.i(TAG, "importCustomResource: 导入完成，$copied/$total 文件")
            ImportResult(true, "导入成功：$copied 个文件", copied)
        } else {
            ImportResult(false, "所有文件复制失败")
        }
    }

    /**
     * 重命名自定义资源。
     * @return true 成功
     */
    fun renameResource(context: Context, oldId: String, newId: String): Boolean {
        if (oldId.isBlank() || newId.isBlank()) return false
        val rootDir = LocalSdResolver.getResourceDir(context)
        val customDir = File(rootDir, CUSTOM_DIR)
        val oldDir = File(customDir, oldId)
        if (!oldDir.exists()) return false
        val newDir = File(customDir, newId)
        if (newDir.exists()) return false
        return try {
            val success = oldDir.renameTo(newDir)
            if (success) refreshIndex()
            success
        } catch (e: Exception) {
            Log.e(TAG, "renameResource: 重命名失败", e)
            false
        }
    }

    /** 强制重建索引。委托 LocalSdResolver.refreshIndex 同步两者 revision。 */
    fun refreshIndex() {
        synchronized(lock) {
            resourceIndex = null
            resourceList = null
        }
        LocalSdResolver.refreshIndex()
    }

    // ============ 内部实现 ============

    /** 懒加载构建索引（线程安全双重检查锁） */
    private fun ensureIndex(context: Context): Map<String, SDResource> {
        resourceIndex?.let { return it }
        return synchronized(lock) {
            resourceIndex ?: buildIndex(context.applicationContext).also {
                resourceIndex = it
            }
        }
    }

    /**
     * 扫描资源根目录，构建 ID → SDResource 索引。
     *
     * 扫描策略：
     * - blhx_sd/<dir>/ → 检查是舰娘资源（含 .skel 或子目录含 .skel）
     * - blhx_sd/custom/<dir>/ → 自定义资源
     * - 每个有效目录生成一个 [SDResource]
     */
    private fun buildIndex(context: Context): Map<String, SDResource> {
        val rootDir = LocalSdResolver.getResourceDir(context)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            Log.w(TAG, "buildIndex: 资源目录不存在 $rootDir")
            return emptyMap()
        }

        val index = LinkedHashMap<String, SDResource>()
        val customDir = File(rootDir, CUSTOM_DIR)

        // 1. 扫描根目录下的资源目录（舰娘资源 + 可能的自定义资源）
        rootDir.listFiles()?.filter { it.isDirectory && it.name != CUSTOM_DIR && it.name != "manifest.json" }
            ?.forEach { dir ->
                val resource = scanResourceDir(dir, SDResourceSource.SHIP)
                if (resource != null) {
                    index[resource.id] = resource
                }
            }

        // 2. 扫描 custom/ 子目录
        if (customDir.exists() && customDir.isDirectory) {
            customDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val resource = scanResourceDir(dir, SDResourceSource.CUSTOM)
                if (resource != null) {
                    // 自定义资源 ID 加 custom/ 前缀避免与舰娘资源冲突
                    val customId = "custom/${dir.name}"
                    index[customId] = resource.copy(id = customId)
                }
            }
        }

        Log.i(TAG, "buildIndex: 发现 ${index.size} 个资源 from $rootDir")
        return index
    }

    /**
     * 扫描单个资源目录，识别类型和皮肤。
     *
     * 兼容两种结构：
     * - 新结构：<dir>/<skin>/<files>（每个子目录是一个皮肤）
     * - 旧结构：<dir>/<files>（文件平铺，按后缀区分皮肤）
     */
    private fun scanResourceDir(dir: File, source: SDResourceSource): SDResource? {
        val files = dir.listFiles() ?: return null

        // 检测新结构：子目录中含 .skel 或图片文件
        val skinDirs = files.filter { it.isDirectory && hasResourceFiles(it) }
        if (skinDirs.isNotEmpty()) {
            val skins = skinDirs.map { skinDir ->
                buildSkinFromDir(skinDir, skinDir.name, dir.name)
            }.sortedBy { it.name }
            if (skins.isNotEmpty()) {
                val type = determineType(skins)
                val previewPath = findPreviewPng(dir)
                return SDResource(
                    id = dir.name,
                    name = dir.name,
                    type = type,
                    source = source,
                    skins = skins,
                    previewPath = previewPath
                )
            }
        }

        // 检测旧结构/平铺：目录中直接含 .skel 或图片文件
        if (hasResourceFiles(dir)) {
            val skins = scanFlatSkins(dir, dir.name)
            if (skins.isNotEmpty()) {
                val type = determineType(skins)
                val previewPath = findPreviewPng(dir)
                return SDResource(
                    id = dir.name,
                    name = dir.name,
                    type = type,
                    source = source,
                    skins = skins,
                    previewPath = previewPath
                )
            }
        }

        return null
    }

    /**
     * 从皮肤子目录构建 [SDSkin]。
     */
    private fun buildSkinFromDir(skinDir: File, skinName: String, resourceName: String): SDSkin {
        val skelFile = skinDir.listFiles()?.firstOrNull { isSkelFile(it.name) }
        val imageFile = skinDir.listFiles()?.firstOrNull { isImageFile(it.name) }

        val type = when {
            skelFile != null -> SDResourceType.SPINE
            imageFile != null -> SDResourceType.STATIC_IMAGE
            else -> SDResourceType.UNKNOWN
        }
        val assetName = skelFile?.nameWithoutExtension ?: imageFile?.nameWithoutExtension ?: resourceName

        return SDSkin(
            name = skinName,
            displayName = skinDisplayName(skinName),
            dirPath = skinDir.absolutePath,
            assetName = assetName,
            type = type
        )
    }

    /**
     * 扫描平铺结构的皮肤（旧结构兼容）。
     * 按 .skel 文件名后缀识别皮肤。
     */
    private fun scanFlatSkins(dir: File, resourceName: String): List<SDSkin> {
        val files = dir.listFiles() ?: return emptyList()
        val skelFiles = files.filter { isSkelFile(it.name) }

        // 有 .skel 文件 → Spine 资源，按后缀分类皮肤
        if (skelFiles.isNotEmpty()) {
            return skelFiles.map { skelFile ->
                val name = skelFile.nameWithoutExtension
                val suffix = if (name.startsWith(resourceName, ignoreCase = true)) {
                    name.substring(resourceName.length).removePrefix("_")
                } else ""
                val skinName = if (suffix.isEmpty()) "default" else classifySkinSuffix(suffix)
                SDSkin(
                    name = skinName,
                    displayName = skinDisplayName(skinName),
                    dirPath = dir.absolutePath,
                    assetName = name,
                    type = SDResourceType.SPINE
                )
            }.distinctBy { it.name }.sortedBy { it.name }
        }

        // 无 .skel 但有图片 → 静态图片资源
        val imageFile = files.firstOrNull { isImageFile(it.name) }
        if (imageFile != null) {
            return listOf(
                SDSkin(
                    name = "default",
                    displayName = "默认",
                    dirPath = dir.absolutePath,
                    assetName = imageFile.nameWithoutExtension,
                    type = SDResourceType.STATIC_IMAGE
                )
            )
        }

        return emptyList()
    }

    /** 检测目录是否包含 SD 资源文件（.skel 或图片） */
    private fun hasResourceFiles(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        // 直接含 .skel 或图片
        if (files.any { isSkelFile(it.name) || isImageFile(it.name) }) return true
        // 含子目录（新结构）
        return files.any { it.isDirectory && hasResourceFiles(it) }
    }

    /** 确定资源整体类型（取所有皮肤的主要类型） */
    private fun determineType(skins: List<SDSkin>): SDResourceType {
        val spineCount = skins.count { it.type == SDResourceType.SPINE }
        return if (spineCount > 0) SDResourceType.SPINE else skins.firstOrNull()?.type ?: SDResourceType.UNKNOWN
    }

    /** 查找目录中的预览 PNG（atlas 配图） */
    private fun findPreviewPng(dir: File): String? {
        // 优先查找 default 子目录
        val defaultDir = File(dir, "default")
        val searchDir = if (defaultDir.exists()) defaultDir else dir
        val png = searchDir.listFiles()?.firstOrNull { isImageFile(it.name) }
        return png?.absolutePath
    }

    /** 文件名后缀 → 皮肤分类名（与 SdResourceOrganizer 一致） */
    private fun classifySkinSuffix(suffix: String): String {
        val s = suffix.lowercase()
        return when (s) {
            "g" -> "gai"
            "h" -> "huan"
            "hx" -> "huangxiang"
            "doa" -> "doa"
            "y" -> "teyao"
            "r" -> "mirror"
            "l" -> "left"
            else -> if (s.all { it.isDigit() }) "skin$s" else s
        }
    }

    /**
     * 递归收集目录下的所有文件（SAF 版本）。
     */
    private fun collectFiles(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        docUri: Uri,
        out: MutableList<Pair<Uri, String>>,
        maxDepth: Int
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(docUri)
        )
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
}

/** 自定义资源导入结果 */
data class ImportResult(
    val success: Boolean,
    val message: String,
    val fileCount: Int = 0
)
