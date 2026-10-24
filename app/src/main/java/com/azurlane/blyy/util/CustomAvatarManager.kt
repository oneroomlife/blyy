package com.azurlane.blyy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 舰娘自定义头像管理器
 *
 * 船坞长按菜单「自定义图片」的存储与解析层：
 * - 存储：`filesDir/custom_avatars/{archiveType}/{URL编码舰名}.png`，
 *   文件名可逆（URLEncoder），无需维护额外索引；archiveType 子目录隔离
 *   同名舰娘（DOCK/STUDENT）。
 * - 解析：ShipImage 的头像解析链第一优先级——
 *   自定义 > 本地 assets 婚皮/默认（[LocalAvatarResolver]）> 网络 URL。
 *   用户显式选择的自定义图覆盖誓约婚皮变体。
 * - 失效：[revision] 单调递增版本号，apply/remove 时递增；
 *   Compose 层将其纳入 remember key 与 ImageRequest.memoryCacheKey
 *   （同路径文件被覆盖时内容变了但路径没变，Coil 内存缓存会一直命中旧图，
 *   必须用 lastModified 组进缓存键主动失效）。
 *
 * 裁剪数学（[computeCropRect]）与 [com.azurlane.blyy.util.AppIconManager]
 * 的方形图标裁剪同源，泛化到任意宽高比的裁剪框：
 * 预览层图片以 ContentScale.Crop 填满裁剪框（baseScale = max 比例），
 * 用户在此基础上叠加 scale 缩放与 offset 平移；裁剪区域反推回原图坐标。
 */
object CustomAvatarManager {

    private const val TAG = "CustomAvatarManager"
    private const val ROOT_DIR = "custom_avatars"

    /** 自定义头像输出长边（px）：卡片显示宽度约 120dp（高密度 ~320px），720px 足够且控制文件体积 */
    private const val OUTPUT_MAX_SIDE = 720

    /** 供 Compose 订阅的版本号：apply/remove 后所有 ShipImage 重组并按新缓存键加载 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 裁剪结果（原图坐标系） */
    data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * 计算裁剪区域（纯函数，JVM 可单测）。
     *
     * @param srcW/srcH 原图尺寸
     * @param boxW/boxH 裁剪框尺寸（px，与预览层显示一致）
     * @param scale 用户缩放（≥1）
     * @param offsetX/offsetY 用户平移（预览层图片中心相对裁剪框中心的偏移）
     */
    fun computeCropRect(
        srcW: Int,
        srcH: Int,
        boxW: Float,
        boxH: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): CropRect {
        // ContentScale.Crop 的基础缩放：等比缩放原图至完全覆盖裁剪框
        val baseScale = max(boxW / srcW, boxH / srcH)
        val actualScale = baseScale * scale

        // 裁剪框两维反推到原图坐标；Crop 覆盖保证约束维等于原图对应维，另一维 ≤
        val cropW = (boxW / actualScale).coerceAtMost(srcW.toFloat())
        val cropH = (boxH / actualScale).coerceAtMost(srcH.toFloat())

        // 图片中心相对裁剪框中心偏移 offset → 原图坐标反推；Crop 默认居中
        val centerX = srcW / 2f - offsetX / actualScale
        val centerY = srcH / 2f - offsetY / actualScale

        val left = (centerX - cropW / 2f).roundToInt().coerceIn(0, (srcW - cropW).roundToInt())
        val top = (centerY - cropH / 2f).roundToInt().coerceIn(0, (srcH - cropH).roundToInt())
        return CropRect(left, top, cropW.roundToInt(), cropH.roundToInt())
    }

    // ── 解析链 ──

    /** 自定义头像文件（存在才非 null） */
    fun resolveFile(context: Context, shipName: String, archiveType: String?): File? {
        if (shipName.isBlank()) return null
        val type = archiveType?.takeIf { it.isNotBlank() } ?: "DOCK"
        val file = File(File(context.filesDir.resolve(ROOT_DIR), type), encodeName(shipName) + ".png")
        return file.takeIf { it.isFile }
    }

    /** 自定义头像优先，无自定义时返回 fallback */
    fun resolveOrDefault(context: Context, shipName: String, archiveType: String?, fallback: String): String {
        resolveFile(context, shipName, archiveType)?.let { return Uri.fromFile(it).toString() }
        return fallback
    }

    // ── 应用 / 移除 ──

    /**
     * 按裁剪参数生成本地自定义头像并原子落盘。
     *
     * @param sourceUri 本地文件 Uri（file://；网络/相册源须先物化为缓存文件，
     *   相册 content Uri 的读取权限是临时的，不能延迟到本方法再读）
     */
    suspend fun applyFromCrop(
        context: Context,
        sourceUri: Uri,
        shipName: String,
        archiveType: String?,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        boxW: Float,
        boxH: Float
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val path = sourceUri.path
                ?: throw IllegalArgumentException("sourceUri 不是文件 Uri: $sourceUri")
            val srcFile = File(path)
            check(srcFile.isFile) { "源文件不存在: $path" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片: $path" }

            val rect = computeCropRect(
                srcW = bounds.outWidth, srcH = bounds.outHeight,
                boxW = boxW, boxH = boxH,
                scale = scale, offsetX = offsetX, offsetY = offsetY
            )
            require(rect.width > 0 && rect.height > 0) { "裁剪区域无效: $rect" }

            // 区域解码 + 采样：目标输出长边 OUTPUT_MAX_SIDE
            val maxSide = max(rect.width, rect.height)
            var sampleSize = 1
            while (maxSide / (sampleSize * 2) >= OUTPUT_MAX_SIDE) sampleSize *= 2
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(srcFile.absolutePath, false)
                ?: throw IllegalStateException("BitmapRegionDecoder 创建失败: $path")
            val region = decoder.decodeRegion(
                android.graphics.Rect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height),
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
            decoder.recycle()
            checkNotNull(region) { "区域解码失败: $rect" }

            // 精确缩放到目标尺寸（保持裁剪框纵横比）
            val targetW: Int
            val targetH: Int
            if (rect.width >= rect.height) {
                targetW = OUTPUT_MAX_SIDE.coerceAtMost(rect.width)
                targetH = (targetW * (rect.height.toFloat() / rect.width)).roundToInt().coerceAtLeast(1)
            } else {
                targetH = OUTPUT_MAX_SIDE.coerceAtMost(rect.height)
                targetW = (targetH * (rect.width.toFloat() / rect.height)).roundToInt().coerceAtLeast(1)
            }
            val output = if (region.width == targetW && region.height == targetH) region
            else Bitmap.createScaledBitmap(region, targetW, targetH, true).also {
                if (it !== region) region.recycle()
            }

            val outFile = File(File(context.filesDir.resolve(ROOT_DIR), archiveType?.takeIf { it.isNotBlank() } ?: "DOCK"), encodeName(shipName) + ".png")
            outFile.parentFile?.mkdirs()

            // 原子写：先写临时文件再改名，避免半写文件被解析链读到
            val tmp = File(outFile.parentFile, outFile.name + ".tmp")
            tmp.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(outFile)) {
                tmp.delete()
                outFile.delete()
                if (!tmp.renameTo(outFile)) throw IllegalStateException("重命名失败: ${outFile.name}")
            }
            output.recycle()

            _revision.value = _revision.value + 1
            Log.i(TAG, "applyFromCrop: ${outFile.name} ${targetW}x${targetH} (source ${bounds.outWidth}x${bounds.outHeight}, crop ${rect})")
            outFile
        }
    }

    /** 移除自定义头像（恢复默认解析链）。返回是否存在过自定义 */
    fun remove(context: Context, shipName: String, archiveType: String?): Boolean {
        val removed = resolveFile(context, shipName, archiveType)?.delete() == true
        if (removed) {
            _revision.value = _revision.value + 1
            Log.i(TAG, "remove: $shipName")
        }
        return removed
    }

    // ── 源物化 ──

    /**
     * 相册 content Uri → 私有缓存文件。
     *
     * PhotoPicker 返回的 Uri 读取权限是临时的（随回调栈销毁/重启失效），
     * 必须在拿到 Uri 的第一时间拷贝到自有目录，后续裁剪屏/应用流程读自己的文件。
     */
    suspend fun copyToCacheFile(context: Context, source: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(context.cacheDir, "avatar_crop_src_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            dest
        }.onFailure { Log.w(TAG, "copyToCacheFile failed", it) }.getOrNull()
    }

    /** 裁剪源缓存目录清理（应用退出前/裁剪完成后调用，删除所有 avatar_crop_src_* 临时文件） */
    fun cleanCropCache(context: Context) {
        val dir = context.cacheDir
        dir.listFiles { f -> f.name.startsWith("avatar_crop_src_") }?.forEach { it.delete() }
    }

    /** 文件名安全编码（可逆，舰名含 ★·() 等特殊字符） */
    private fun encodeName(shipName: String): String =
        URLEncoder.encode(shipName, "UTF-8")
}
