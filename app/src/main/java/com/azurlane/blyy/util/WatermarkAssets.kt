package com.azurlane.blyy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 水印资源条目。
 *
 * @param id           稳定唯一标识（目录/文件名去掉扩展名），用于路由参数与状态恢复
 * @param displayName  中文显示名（未知资源自动降级为文件名首字母大写）
 * @param assetPath    完整水印图（含透明通道）的 assets 相对路径
 * @param thumbPath    `_small` 缩略图路径；null 表示该水印没有配对的缩略图，
 *                    调用方应回退使用 [assetPath]（配合 Coil 按目标尺寸降采样，不会 OOM）
 * @param width        完整水印图像素宽（0 表示尺寸解码失败）
 * @param height       完整水印图像素高
 */
data class WatermarkEntry(
    val id: String,
    val displayName: String,
    val assetPath: String,
    val thumbPath: String?,
    val width: Int,
    val height: Int
) {
    /** 缩略图路径（无 `_small` 时回退正式图，由 Coil 按目标尺寸降采样兜底） */
    val preferredThumbPath: String get() = thumbPath ?: assetPath
}

/**
 * 水印资源发现器 — 动态扫描 `assets/photo_frame/` 目录。
 *
 * 设计目标：
 * 1. **动态发现**：新增水印 PNG 只需放入 assets/photo_frame/ 目录，无需修改业务代码。
 * 2. **稳健配对**：`_small` 后缀缩略图与正式图通过「去扩展名 + 去后缀后的小写 base 名」索引匹配，
 *    天然容忍大小写差异、扩展名不同（如 xxx.png + xxx_small.webp）、缺 `_small`（回退正式图）等情况。
 * 3. **不崩溃**：任何单个资源解码失败只跳过该项，不影响整个列表。
 *
 * 命名约定（当前资源现状）：
 * - `photo_frame_card.png` — 正式水印（作为叠加素材）
 * - `photo_frame_card_small.png` — 对应缩略图（选择器图标，均为 216×216）
 *
 * 排序规则：`default` 置顶，其余按显示名不分大小写排序，保证列表顺序稳定。
 */
object WatermarkAssets {

    private const val TAG = "WatermarkAssets"

    /** assets 内的水印根目录 */
    const val DIR = "photo_frame"

    /** 缩略图文件名后缀（不含扩展名） */
    private const val SMALL_SUFFIX = "_small"

    /** 水印文件名的统一前缀（仅用于生成显示名时剥离） */
    private const val NAME_PREFIX = "photo_frame_"

    /** 支持的图片扩展名（小写比较） */
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /** 内存缓存：进入相机页/编辑页各加载一次，直接复用 */
    @Volatile
    private var cachedEntries: List<WatermarkEntry>? = null

    /** 缓存专用锁（避免误用外层 CoroutineScope 作为监视器） */
    private val cacheLock = Any()

    /** 资源 id → 中文名映射；后续新增资源可直接在这里补充，未命中的自动降级为文件名 */
    private val DISPLAY_NAMES = mapOf(
        "card" to "卡片相框",
        "counterfoil" to "电影票根",
        "default" to "简约边框",
        "film" to "胶片",
        "focus" to "聚焦取景",
        "ins" to "快拍",
        "phone" to "手机屏幕",
        "picture" to "画框",
        "player" to "随身听",
        "polaroid" to "拍立得",
        "projection" to "幻灯片",
        "train" to "纪念车票",
        "tv" to "老式电视",
        "vcr" to "录像带",
        "wood" to "木质相框"
    )

    /**
     * 加载全部水印条目（带内存缓存）。
     * 在 IO 线程执行 assets 列表与尺寸解码，任何异常返回已成功的子集。
     */
    suspend fun load(context: Context): List<WatermarkEntry> {
        cachedEntries?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                cachedEntries?.let { return@synchronized it }
                val entries = scanDirectory(context.applicationContext)
                cachedEntries = entries
                Log.i(TAG, "load: 发现 ${entries.size} 个水印资源")
                entries
            }
        }
    }

    /** 仅用于测试或资源变更后强制刷新 */
    fun invalidateCache() {
        synchronized(cacheLock) { cachedEntries = null }
    }

    /** 按 id 查找条目（跨页面路由传递后恢复） */
    fun find(entries: List<WatermarkEntry>, id: String?): WatermarkEntry? {
        if (id.isNullOrBlank()) return null
        return entries.firstOrNull { it.id == id }
    }

    /**
     * 解码 assets 图片为 Bitmap（inSampleSize 降采样到 [maxSidePx] 级别）。
     *
     * 缩略图/预览渲染**不走 Coil 的 asset URI 链路**：相框 PNG 大面积透明，
     * 垫底占位图标会从透明区域透出，被误认为"图片加载失败"；直接解码可
     * 精确区分"加载中 / 解码失败 / 成功"。失败返回 null。
     */
    suspend fun decodeScaled(context: Context, path: String?, maxSidePx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSidePx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                Log.e(TAG, "decodeScaled: 解码失败 → $path", e)
                null
            }
        }
    }

    // ==================== 内部扫描逻辑 ====================

    private fun scanDirectory(context: Context): List<WatermarkEntry> {
        val files = try {
            context.assets.list(DIR)?.toList().orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "scanDirectory: 无法列出 $DIR 目录", e)
            return emptyList()
        }

        // 仅保留图片文件（顺带过滤掉可能的子目录名）
        val imageFiles = files.filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in IMAGE_EXTENSIONS
        }
        if (imageFiles.isEmpty()) return emptyList()

        // 建立「小写 base 名 → _small 缩略图路径」索引。
        // 同名不同扩展名时保留排序后的第一个，避免随机覆盖。
        val smallIndex = mutableMapOf<String, String>()
        imageFiles.sorted().forEach { name ->
            val base = name.substringBeforeLast('.').lowercase()
            if (base.endsWith(SMALL_SUFFIX)) {
                val key = base.removeSuffix(SMALL_SUFFIX)
                if (key.isNotEmpty()) smallIndex.putIfAbsent(key, "$DIR/$name")
            }
        }

        // 正式图 → 组装条目（孤立的 _small 自动被跳过，因为它们的后缀不参与正式图遍历）
        val entries = imageFiles.mapNotNull { name ->
            val baseWithExt = name.substringBeforeLast('.')
            if (baseWithExt.lowercase().endsWith(SMALL_SUFFIX)) return@mapNotNull null

            val relativePath = "$DIR/$name"
            val thumb = smallIndex[baseWithExt.lowercase()]
            val (w, h) = decodeAssetSize(context, relativePath)
            if (w <= 0 || h <= 0) {
                // 尺寸异常的正式图仍保留（宽高用 1:1 兜底），但记录日志
                Log.w(TAG, "scanDirectory: 水印尺寸解码失败，已跳过 → $relativePath")
                return@mapNotNull null
            }
            WatermarkEntry(
                id = relativePath.substringBeforeLast('.'),
                displayName = resolveDisplayName(baseWithExt),
                assetPath = relativePath,
                thumbPath = thumb,
                width = w,
                height = h
            )
        }

        // default 置顶，其余按显示名排序（不区分大小写，保证列表顺序稳定可预期）
        return entries.sortedWith(
            compareByDescending<WatermarkEntry> { it.assetPath.contains("$NAME_PREFIX$DEFAULT_ID.", ignoreCase = true) }
                .thenBy { it.displayName.lowercase() }
        )
    }

    /** default 资源（无实际边框的最简水印）的小写 id 片段 */
    private const val DEFAULT_ID = "default"

    private fun resolveDisplayName(baseName: String): String {
        val stripped = baseName.removePrefix(NAME_PREFIX)
        return DISPLAY_NAMES[stripped.lowercase()] ?: run {
            if (stripped.isEmpty()) baseName
            else stripped.replaceFirstChar { it.uppercase() }
        }
    }

    /** 仅解码 PNG/JPG 头部尺寸（inJustDecodeBounds，不占位图像内存） */
    private fun decodeAssetSize(context: Context, path: String): Pair<Int, Int> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
            opts.outWidth to opts.outHeight
        } catch (e: Exception) {
            Log.e(TAG, "decodeAssetSize: 解码失败 → $path", e)
            0 to 0
        }
    }
}
