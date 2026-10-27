package com.azurlane.blyy.util.gameimport

import android.content.Context
import android.util.Log
import com.azurlane.blyy.util.AvatarAssetIndex
import com.azurlane.blyy.util.Live2dLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏资源库：四类导入资源的磁盘布局、扫描与增删改。
 *
 * 存储约定：
 * - Live2D 仍归 [Live2dLibrary] 管理（查看器 / 陪伴模式都依赖那个根目录，不做迁移）；
 * - 其余三类放到 `<app 外部私有目录>/game_assets/<kind>/<id>/`，与 Live2D 平级，
 *   免权限、adb 可直接推送；
 * - 解包暂存目录 `.game_assets_staging/<kind>/` 放在同级但不属于资源根，
 *   半成品不会被 [scan] 当成坏资源。
 */
@Singleton
class GameAssetLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val live2dLibrary: Live2dLibrary,
    private val avatarAssets: AvatarAssetIndex
) {
    companion object {
        private const val TAG = "GameAssetLibrary"
        private const val ROOT_NAME = "game_assets"
        private const val STAGING_NAME = ".game_assets_staging"
    }

    private fun baseDir(): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** 某类资源的根目录（不存在时自动创建） */
    fun rootDir(kind: GameAssetKind): File = when (kind) {
        GameAssetKind.LIVE2D -> live2dLibrary.rootDir()
        else -> File(baseDir(), "$ROOT_NAME/${kind.key}").apply { if (!exists()) mkdirs() }
    }

    fun rootPath(kind: GameAssetKind): String = rootDir(kind).absolutePath

    /** 解包暂存目录（与资源根同分区，rename 才是原子操作） */
    fun stagingDir(kind: GameAssetKind): File =
        File(baseDir(), "$STAGING_NAME/${kind.key}").apply { if (!exists()) mkdirs() }

    fun isValidId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..") &&
            !id.startsWith('.')

    /** 单个资源的目录 */
    fun dirOf(kind: GameAssetKind, id: String): File = File(rootDir(kind), id)

    suspend fun scanAll(): Map<GameAssetKind, List<GameAssetInfo>> = withContext(Dispatchers.IO) {
        GameAssetKind.ordered.associateWith { kind ->
            runCatching { scan(kind) }
                .onFailure { Log.w(TAG, "扫描 ${kind.key} 失败", it) }
                .getOrDefault(emptyList())
        }
    }

    suspend fun scan(kind: GameAssetKind): List<GameAssetInfo> = withContext(Dispatchers.IO) {
        when (kind) {
            GameAssetKind.LIVE2D -> scanLive2d()
            GameAssetKind.PAINTING -> scanPaintings()
            else -> scanSpineAssets(kind)
        }
    }

    suspend fun find(kind: GameAssetKind, id: String): GameAssetInfo? = withContext(Dispatchers.IO) {
        if (!isValidId(id)) return@withContext null
        val dir = dirOf(kind, id)
        if (!dir.isDirectory) return@withContext null
        runCatching { toInfo(kind, dir) }.getOrNull()
    }

    /**
     * 删除一个资源目录（图片/骨骼/侧车一并删除）。
     * Live2D 走原有库，保证缩略图缓存也被清掉。
     */
    suspend fun delete(kind: GameAssetKind, id: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidId(id)) return@withContext false
        if (kind == GameAssetKind.LIVE2D) return@withContext live2dLibrary.delete(id)
        val dir = dirOf(kind, id)
        val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (!ok) Log.w(TAG, "删除失败：${kind.key}/$id")
        ok || !dir.exists()
    }

    /** 重命名：目录内引用全为相对路径，直接改目录名安全 */
    suspend fun rename(kind: GameAssetKind, id: String, newName: String): String? =
        withContext(Dispatchers.IO) {
            if (kind == GameAssetKind.LIVE2D) return@withContext live2dLibrary.rename(id, newName)
            val sanitized = GameAssetNaming.sanitizeId(newName)
            when {
                !isValidId(id) -> "无效的资源 ID"
                newName.isBlank() -> "名称不能为空"
                sanitized == id -> null
                dirOf(kind, sanitized).exists() -> "已存在同名资源"
                !dirOf(kind, id).isDirectory -> "资源不存在"
                !dirOf(kind, id).renameTo(dirOf(kind, sanitized)) -> "重命名失败（文件可能被占用）"
                else -> null
            }
        }

    // ------------------------------------------------------------------ 内部：扫描

    private suspend fun scanLive2d(): List<GameAssetInfo> {
        val (models, invalid) = live2dLibrary.scan()
        if (invalid.isNotEmpty()) {
            Log.i(TAG, "Live2D 无效目录：${invalid.joinToString { it.id + "(" + it.reason + ")" }}")
        }
        return models.map { model ->
            GameAssetInfo(
                kind = GameAssetKind.LIVE2D,
                id = model.id,
                dir = model.dir,
                sizeBytes = model.sizeBytes,
                fileCount = model.fileCount,
                pageCount = model.textures.size,
                previewFile = model.thumbFile,
                avatarAsset = model.avatarAsset,
                variantLabel = GameAssetNaming.variantOf(model.id)?.second,
                sourceNote = model.motionGroups.size.takeIf { it > 0 }?.let { "$it 组动作" }
            )
        }
    }

    private fun scanSpineAssets(kind: GameAssetKind): List<GameAssetInfo> {
        val root = rootDir(kind)
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        return dirs.mapNotNull { dir ->
            runCatching { toSpineInfo(kind, dir) }
                .onFailure { Log.w(TAG, "解析 Spine 资源失败：${dir.name}", it) }
                .getOrNull()
        }
    }

    private fun scanPaintings(): List<GameAssetInfo> {
        val root = rootDir(GameAssetKind.PAINTING)
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        return dirs.mapNotNull { dir ->
            runCatching { toPaintingInfo(dir) }
                .onFailure { Log.w(TAG, "解析立绘资源失败：${dir.name}", it) }
                .getOrNull()
        }
    }

    private suspend fun toInfo(kind: GameAssetKind, dir: File): GameAssetInfo? = when (kind) {
        GameAssetKind.LIVE2D -> scanLive2d().firstOrNull { it.id == dir.name }
        GameAssetKind.PAINTING -> toPaintingInfo(dir)
        else -> toSpineInfo(kind, dir)
    }

    private fun toSpineInfo(kind: GameAssetKind, dir: File): GameAssetInfo? {
        val meta = AssetMetaStore.readSpine(dir)
        val skeletonFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".skel", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val skeletons = meta?.skeletons?.map { it.name }
            ?: skeletonFiles.map { it.nameWithoutExtension }
        if (skeletons.isEmpty()) return null
        val primary = meta?.primary?.takeIf { it in skeletons }
            ?: skeletons.minByOrNull { it.length }
            ?: skeletons.first()
        val version = meta?.skeletons?.firstOrNull { it.name == primary }?.version
            ?: SpineBinaryHeader.readVersion(File(dir, "$primary.skel"))
        val pages = meta?.skeletons?.flatMap { it.pages }?.distinct()
            ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }?.map { it.name }
            ?: emptyList()
        val (size, count) = measure(dir)
        return GameAssetInfo(
            kind = kind,
            id = dir.name,
            dir = dir,
            sizeBytes = size,
            fileCount = count,
            skeletons = skeletons,
            primarySkeleton = primary,
            spineVersion = version,
            pageCount = pages.size,
            avatarAsset = avatarAssets.resolve(dir.name),
            variantLabel = GameAssetNaming.variantOf(dir.name)?.second,
            versionLabel = version?.let { "Spine $it" }
        )
    }

    private fun toPaintingInfo(dir: File): GameAssetInfo? {
        val meta = AssetMetaStore.readPainting(dir)
        val image = File(dir, meta?.file ?: AssetFiles.PAINTING_IMAGE)
        if (!image.isFile) return null
        val (size, count) = measure(dir)
        val dims = meta?.takeIf { it.width > 0 && it.height > 0 }
        return GameAssetInfo(
            kind = GameAssetKind.PAINTING,
            id = dir.name,
            dir = dir,
            sizeBytes = size,
            fileCount = count,
            pageCount = 1,
            imageWidth = dims?.width ?: 0,
            imageHeight = dims?.height ?: 0,
            previewFile = image,
            avatarAsset = avatarAssets.resolve(dir.name),
            variantLabel = GameAssetNaming.variantOf(dir.name)?.second,
            // 分辨率由 imageWidth/imageHeight 展示，这里不再重复写 versionLabel
            versionLabel = null
        )
    }

    private fun measure(dir: File): Pair<Long, Int> {
        var size = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                size += f.length()
                count++
            }
        }
        return size to count
    }
}
