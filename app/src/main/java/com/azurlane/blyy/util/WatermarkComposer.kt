package com.azurlane.blyy.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 照片摆放可调参数 — 预览与合成共用同一份数据，保证所见即所得。
 * 以水印（相框）为基准的模型：画布 = 相框宽高比，以下参数描述**照片**在画布内的摆放。
 *
 * 照片基准布局固定为「铺满外切」（等比缩放至完全覆盖相框画布，超出部分被裁切），
 * 用户通过**双指缩放**（scale）与**单指拖动**（offsetX/offsetY）自由调整。
 *
 * @param scale   照片相对基准尺寸的缩放倍率（1f = 基准尺寸）
 * @param offsetX 归一化水平偏移：照片中心相对相框中心的位移比例。
 *                ±1 表示该方向上照片边缘与相框边缘对齐（覆盖画布）；
 *                放大后偏移可超出 ±1（照片边缘进入画布内部、露出背景），
 *                实际范围由 [WatermarkComposer.clampPhotoCenter] 的物理边界决定 ——
 *                拖动范围随照片尺寸自适应、最大化。照片与相框等大时该轴偏移无效
 * @param offsetY 归一化垂直偏移（同上）
 */
data class WatermarkParams(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    companion object {
        val Default = WatermarkParams()
        /** 手势缩放区间：上限放宽至 12 倍基准（铺满外切），配合夹住检测的 epsilon
         *  修复，上限真正可达 —— 可放大裁切到只显示照片局部 */
        const val SCALE_MIN = 0.2f
        const val SCALE_MAX = 12.0f
    }
}

/** 照片在容器（相框画布/预览框）内的最终摆放位置（单位与容器一致，px） */
data class WatermarkPlacement(
    val width: Float,
    val height: Float,
    val left: Float,
    val top: Float
)

/** 保存结果 */
sealed class SaveResult {
    data class Success(val uri: Uri) : SaveResult()
    /** Android 9 及以下写公共图库缺少权限，UI 层应请求 [permission] 后重试 */
    data class NeedPermission(val permission: String) : SaveResult()
    data class Error(val message: String) : SaveResult()
}

/**
 * 水印合成引擎 — 原始照片 + 相框水印 → 导出图片。
 *
 * **以水印（相框）为基准的合成模型**：输出画布的宽高比与相框一致，相框始终完整覆盖
 * 画布；照片作为内容按 [WatermarkParams] 摆放进画布。从根本上避免了"水印无法
 * 覆盖完整照片"的问题。
 *
 * 设计要点：
 * - **布局算法单一来源**：[computePlacement] 同时服务编辑页实时预览与最终合成，
 *   两边以相同入参（容器尺寸、照片原始尺寸、参数）计算，实现像素级所见即所得。
 * - **输出分辨率策略**：长边取照片与相框中的较大者，但限制相框最多放大 2 倍
 *   （小尺寸相框放宽至至少 1080px），上限 [MAX_DIMENSION] — 照片与相框都不被无谓降采样。
 * - **内存管理**：解码上限 [MAX_DIMENSION]（约 4096px，16MP 级别），超出自动降采样；
 *   合成在 [Dispatchers.Default] 执行；中间 Bitmap 用完立即 recycle。
 * - **EXIF 处理**：API 28+ ImageDecoder 自动应用方向；API 24-27 手动 ExifInterface
 *   处理全部 8 种方向（含镜像翻转），与预览侧解码行为一致。
 */
object WatermarkComposer {

    private const val TAG = "WatermarkComposer"

    /** 解码原图的最大边长（防止超大图 OOM；12MP 相机照片 ~4000px 完整保留） */
    private const val MAX_DIMENSION = 4096

    /** JPEG 导出质量 */
    private const val JPEG_QUALITY = 92

    /** 导出相册的相对目录 */
    private val GALLERY_DIR: String = Environment.DIRECTORY_PICTURES + "/水印相机"

    /** 文件名前缀 + 时间戳格式，天然避免重名（系统仍会对极端重名自动追加序号） */
    private const val FILE_PREFIX = "BLYY_WM_"

    // ==================== 布局算法（预览 & 合成共用） ====================

    /**
     * 计算照片在指定容器（相框画布/预览框）内的摆放位置。
     *
     * 基准尺寸固定为「铺满外切」：max(容器/照片 宽高比)，照片等比缩放至完全覆盖
     * 容器，再乘以用户手势 scale。归一化偏移 ±1 恰好映射为「照片边缘对齐容器边缘」；
     * 照片与容器等大时该轴自动失去自由度。
     */
    fun computePlacement(
        containerW: Float,
        containerH: Float,
        wmW: Float,
        wmH: Float,
        params: WatermarkParams
    ): WatermarkPlacement {
        if (containerW <= 0f || containerH <= 0f || wmW <= 0f || wmH <= 0f) {
            return WatermarkPlacement(0f, 0f, 0f, 0f)
        }
        val base = maxOf(containerW / wmW, containerH / wmH)
        val s = base * params.scale
        val w = wmW * s
        val h = wmH * s
        val cx = containerW / 2f + params.offsetX * (containerW - w) / 2f
        val cy = containerH / 2f + params.offsetY * (containerH - h) / 2f
        return WatermarkPlacement(w, h, cx - w / 2f, cy - h / 2f)
    }

    /**
     * 归一化偏移的兜底上限（防异常数据）。正常范围不由它决定 ——
     * 由 [clampPhotoCenter] 的物理边界约束，照片放大后拖动范围最大化。
     */
    const val OFFSET_SANITY_LIMIT = 100f

    /**
     * 把照片中心点限制在物理合法范围 —— **解决"放大后可拖动范围不够"**。
     *
     * 按归一化偏移 clamp 不够的原因：物理拖动距离 = offset × (画布−照片)/2，
     * scale 略大于 1 时照片只比画布大一点，该系数极小（如 scale=1.2 时中心
     * 位移上限仅 ±44px），拖动几乎无感。这里直接按**照片当前尺寸**约束中心点：
     *
     * - 照片 ≥ 画布：允许照片边缘与画布边缘相切（中心位移上限 (画布+照片)/2），
     *   照片任意局部都能对准画布窗口，拖动范围最大化；
     * - 照片 < 画布：照片必须整体位于画布内（中心在 [照片/2, 画布−照片/2]）。
     *
     * @param center        照片中心点（未约束，px）
     * @param containerSize 画布该轴边长（px）
     * @param photoSize     照片该轴当前边长（px，已含 scale）
     */
    fun clampPhotoCenter(center: Float, containerSize: Float, photoSize: Float): Float {
        val minC = if (photoSize >= containerSize) -photoSize / 2f else photoSize / 2f
        val maxC = if (photoSize >= containerSize) containerSize + photoSize / 2f else containerSize - photoSize / 2f
        return center.coerceIn(minC, maxC)
    }

    // ==================== 高质量缩放管线（预览 & 合成共用） ====================

    /** 小尺寸相框放宽的输出长边下限（保证低分辨率相框也有可用的成片分辨率） */
    private const val MIN_OUTPUT_LONG_SIDE = 1080

    /**
     * 计算合成输出画布尺寸（像素）。宽高比 = 相框 PNG 宽高比（含 roundToInt 取整）。
     *
     * **预览与合成必须共用本函数确定容器比例**：编辑页预览容器按
     * `outW.toFloat()/outH` 适配（而非相框 PNG 的精确比例），合成画布直接用
     * 返回值 —— 两侧容器宽高比严格一致，[computePlacement] 的归一化结果
     * （照片相对画布的位置）在预览与保存之间 100% 一致，消除"不同水印下
     * 预览与保存照片位置偏移不同"的问题。
     *
     * 长边策略（见类注释）：跟随照片分辨率，但相框最多放大 2 倍
     * （小尺寸相框放宽到至少 1080px），且相框绝不降采样，上限 [MAX_DIMENSION]。
     */
    fun outputCanvasSize(
        watermarkW: Int,
        watermarkH: Int,
        photoW: Int,
        photoH: Int
    ): Pair<Int, Int> {
        if (watermarkW <= 0 || watermarkH <= 0) return watermarkW to watermarkH
        val wmLong = maxOf(watermarkW, watermarkH)
        val photoLong = maxOf(photoW, photoH)
        val longSide = photoLong
            .coerceIn(wmLong, maxOf(wmLong * 2, MIN_OUTPUT_LONG_SIDE))
            .coerceAtMost(MAX_DIMENSION)
        val k = longSide.toFloat() / wmLong
        val outW = (watermarkW * k).roundToInt().coerceAtLeast(1)
        val outH = (watermarkH * k).roundToInt().coerceAtLeast(1)
        return outW to outH
    }

    /**
     * 高质量缩放位图：迭代减半（等价于多级盒滤波，保留高频细节、消除锯齿与
     * 摩尔纹）+ 最后一步双线性精调到目标尺寸。
     * 单次双线性大幅缩小会随机丢弃像素行/列，细线纹理会闪烁断裂 — 这是
     * "贴图感"与"印刷感"差距的主要来源之一。
     *
     * 单槽缓存（[cacheable] = true 时启用）：预览中相框画布尺寸在拖动/调参时
     * 保持不变，缓存命中后每帧直接复用缩放结果。**合成导出等一次性大位图场景
     * 必须传 false** — 避免把最大可达数十 MB 的中间位图滞留在静态缓存里。
     * OOM 等异常返回 null（调用方跳过或降级绘制）。
     */
    fun scaleHighQuality(
        src: Bitmap,
        destW: Int,
        destH: Int,
        cacheable: Boolean = true
    ): Bitmap? {
        val w = destW.coerceAtLeast(1)
        val h = destH.coerceAtLeast(1)
        return try {
            if (cacheable) {
                synchronized(layerCacheLock) {
                    val c = layerCache
                    if (c != null && c.srcRef.get() === src && c.targetW == w && c.targetH == h) {
                        return c.scaled
                    }
                }
            }
            val scaled = highQualityScale(src, w, h)
            if (cacheable) {
                synchronized(layerCacheLock) {
                    layerCache = LayerCacheEntry(WeakReference(src), w, h, scaled)
                }
            }
            scaled
        } catch (t: Throwable) {
            // OOM / 缩放失败：返回 null 由调用方降级
            Log.e(TAG, "scaleHighQuality: 失败 ${src.width}x${src.height} -> ${w}x$h", t)
            null
        }
    }

    private fun highQualityScale(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src
        var cur = src
        while (cur.width >= targetW * 2 && cur.height >= targetH * 2) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        if (cur.width != targetW || cur.height != targetH) {
            cur = Bitmap.createScaledBitmap(cur, targetW, targetH, true)
        }
        return cur
    }

    /** 缩放结果单槽缓存条目（WeakReference 校验源位图，防止跨会话误命中） */
    private class LayerCacheEntry(
        val srcRef: WeakReference<Bitmap>,
        val targetW: Int,
        val targetH: Int,
        val scaled: Bitmap
    )

    private val layerCacheLock = Any()
    @Volatile
    private var layerCache: LayerCacheEntry? = null

    // ==================== 解码 ====================

    /**
     * 解码原始照片为可绘制的软件 Bitmap：
     * - 自动应用 EXIF 方向（含镜像翻转，与 Coil/ImageDecoder 行为一致）
     * - 超过 [MAX_DIMENSION] 自动降采样，防止 OOM
     * - 失败返回 null（调用方提示用户，不崩溃）
     *
     * 编辑页应把解码结果作为**单一数据源**持有：预览与合成共用同一 Bitmap，
     * 从根上消除两套解码管线（Coil 与 ImageDecoder/legacy）的差异，
     * 保证"预览 = 保存"像素级一致。
     */
    suspend fun decodeSourceBitmap(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        // Canvas 绘制要求软件位图（默认可能是 HARDWARE）
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val maxSide = maxOf(info.size.width, info.size.height)
                        if (maxSide > MAX_DIMENSION) {
                            decoder.setTargetSampleSize((maxSide / MAX_DIMENSION).coerceAtLeast(1))
                        }
                    }
                    // ImageDecoder 自动应用 EXIF 旋转，无需手动处理
                } else {
                    decodeLegacyWithExif(context, uri)
                }
            } catch (e: Exception) {
                // 覆盖：文件不存在/损坏/格式异常/内存不足
                Log.e(TAG, "decodeSourceBitmap: 解码失败 uri=$uri", e)
                null
            }
        }
    }

    /** API 24-27：BitmapFactory + inSampleSize 降采样 + ExifInterface 手动旋转 */
    private fun decodeLegacyWithExif(context: Context, uri: Uri): Bitmap? {
        // 1. 读取边界
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2. 计算降采样倍率
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2

        // 3. 解码
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // 4. EXIF 旋转
        return rotateByExif(bitmap, readExifOrientation(context, uri))
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "readExifOrientation: 读取失败 uri=$uri", e)
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun rotateByExif(bitmap: Bitmap, orientation: Int): Bitmap {
        // EXIF 8 种方向全部处理（含镜像翻转）—— 与 ImageDecoder/Coil 行为一致。
        // 此前仅处理 90/180/270 旋转：TRANSPOSE/TRANSVERSE/FLIP 类方向会得到
        // 左右颠倒的位图，与预览不一致
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap // NORMAL / 未知方向：无需处理
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "rotateByExif: 方向变换失败", e)
            bitmap
        }
    }

    /**
     * 加载水印 PNG（保留透明通道）供预览渲染；失败返回 null。
     * [maxSidePx] 指定需要的最大边长（预览传目标尺寸的 1~2 倍即可，按 inSampleSize
     * 降采样解码，避免为预览解码全分辨率相框造成内存尖峰）；合成导出用
     * [WatermarkAssets.decodeScaled] 的全分辨率路径。
     */
    suspend fun loadWatermark(
        context: Context,
        entry: WatermarkEntry,
        maxSidePx: Int = Int.MAX_VALUE
    ): Bitmap? = WatermarkAssets.decodeScaled(context, entry.assetPath, maxSidePx)

    // ==================== 合成 ====================

    /**
     * 合成最终图片：以水印（相框）为基准的画布 + 照片内容。
     *
     * - 画布宽高比与相框一致，相框始终完整覆盖画布 → 永远不会出现"水印盖不住照片"
     * - 照片按 [params]（手势缩放/拖动偏移）摆放进画布，与编辑页预览同参计算
     * - 未选水印：直接导出原图（保存时统一 JPEG 重编码）
     *
     * @param sourceBitmap 编辑页已解码的位图（预览与合成共用同一数据源，保证所见即所得）。
     *                     传入时本方法**不回收**该位图（归调用方管理）；
     *                     null 时内部解码并自行负责回收。
     * @return 合成后的 Bitmap（调用方负责 recycle 或交给保存流程消费）；解码失败返回 null
     */
    suspend fun compose(
        context: Context,
        sourceUri: Uri,
        entry: WatermarkEntry?,
        params: WatermarkParams,
        sourceBitmap: Bitmap? = null
    ): Bitmap? {
        return withContext(Dispatchers.Default) {
            val source = sourceBitmap ?: decodeSourceBitmap(context, sourceUri)
                ?: return@withContext null

            // 未选水印：直接导出原图（保存时统一 JPEG 重编码）
            if (entry == null) return@withContext source

            val watermark = WatermarkAssets.decodeScaled(context, entry.assetPath, Int.MAX_VALUE)
            if (watermark == null) {
                // 水印资源缺失时不阻断导出，降级为无水印原图
                Log.w(TAG, "compose: 水印不可用，按原图导出")
                return@withContext source
            }

            try {
                // ---- 输出画布：宽高比 = 相框宽高比 ----
                // 与预览共用 outputCanvasSize：两侧容器宽高比（含 roundToInt 取整）
                // 严格一致 → [computePlacement] 的归一化结果在预览与保存之间 100% 一致
                val (outW, outH) = outputCanvasSize(
                    watermark.width, watermark.height,
                    source.width, source.height
                )

                val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                try {
                    // 1) 照片：摆放进相框画布（布局算法与预览共用，高质量缩放）
                    val placement = computePlacement(
                        outW.toFloat(), outH.toFloat(),
                        source.width.toFloat(), source.height.toFloat(),
                        params
                    )
                    val photoScaled = scaleHighQuality(
                        source,
                        placement.width.roundToInt(), placement.height.roundToInt(),
                        cacheable = false // 一次性大位图，禁止进入静态缓存
                    )
                    if (photoScaled != null) {
                        canvas.drawBitmap(photoScaled, placement.left, placement.top, null)
                    } else {
                        // OOM 降级：直接按目标矩形绘制原图（质量略差但不中断导出）
                        canvas.drawBitmap(
                            source, null,
                            RectF(
                                placement.left, placement.top,
                                placement.left + placement.width, placement.top + placement.height
                            ),
                            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                        )
                    }

                    // 2) 相框：覆盖整幅画布
                    val frameScaled = scaleHighQuality(watermark, outW, outH, cacheable = false)
                        ?: watermark // OOM 时按原尺寸绘制（画布尺寸=比例一致，位置仍正确）
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    if (frameScaled !== watermark) {
                        canvas.drawBitmap(frameScaled, 0f, 0f, paint)
                    } else {
                        canvas.drawBitmap(
                            frameScaled, null,
                            RectF(0f, 0f, outW.toFloat(), outH.toFloat()),
                            paint
                        )
                    }
                } catch (e: Exception) {
                    // 照片/相框绘制异常：输出退化为空白画布 + 原图内容已尽力绘制，不中断保存
                    Log.e(TAG, "compose: 合成绘制失败", e)
                }
                if (sourceBitmap == null) {
                    source.recycle() // 内部解码的原图已绘制到输出，尽早释放
                }
                output
            } catch (e: Exception) {
                // createBitmap OOM：source 尚未被回收，安全降级返回
                Log.e(TAG, "compose: 合成失败，按原图导出", e)
                source
            } finally {
                watermark.recycle()
            }
        }
    }

    // ==================== 拍照临时文件管理 ====================

    /** 拍照临时子目录名（位于应用 cache，系统低磁盘时自动清理，不占用用户存储） */
    private const val CAPTURE_DIR_NAME = "watermark_camera"

    /** 过期清理阈值：超过 1 小时的历史拍照文件（正常流程分钟级完成编辑） */
    private const val STALE_THRESHOLD_MS = 60L * 60 * 1000

    /**
     * 创建拍照输出临时文件（目录懒创建）。
     * 命名含毫秒级时间戳，连续快速拍照不冲突。
     */
    fun createCaptureFile(context: Context): java.io.File {
        val dir = java.io.File(context.cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }
        val name = "capture_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        return java.io.File(dir, name)
    }

    /**
     * 将图库等外部 [Uri] 固化为编辑期可控的本地缓存文件。
     *
     * Photo Picker 返回的 `content://` 读取授权通常只在短时间内有效；编辑页还会经历
     * 导航、配置变化和异步解码，直接持有该 Uri 会使预览或保存随机出现“图片已失效”。
     * 相机拍摄的文件本来就在本模块缓存目录中，直接复用，避免无谓复制。
     *
     * 返回的 file Uri 与拍照文件共用生命周期，离开编辑页后由
     * [deleteIfTempCaptureFile] 清理。复制失败返回 null，调用方显示读取失败状态。
     */
    suspend fun materializeSourceForEditing(context: Context, uri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                if (isTempCaptureFile(context, uri)) return@withContext uri
                val dir = java.io.File(context.cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }
                val name = "source_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.img"
                val target = java.io.File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (target.length() <= 0L) {
                    target.delete()
                    return@withContext null
                }
                Uri.fromFile(target)
            } catch (e: Exception) {
                Log.e(TAG, "materializeSourceForEditing: 复制失败 uri=$uri", e)
                null
            }
        }
    }

    /** 判断 Uri 是否为本模块拍照临时文件（file scheme + cache 目录前缀） */
    fun isTempCaptureFile(context: Context, uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "file") return false
        val path = uri.path ?: return false
        return path.startsWith(java.io.File(context.cacheDir, CAPTURE_DIR_NAME).absolutePath)
    }

    /** 若为拍照临时文件则删除（静默失败 — cache 文件最终由系统回收） */
    fun deleteIfTempCaptureFile(context: Context, uri: Uri?) {
        if (!isTempCaptureFile(context, uri)) return
        val path = uri?.path ?: return
        try {
            java.io.File(path).delete()
            Log.d(TAG, "deleteIfTempCaptureFile: 已清理 $path")
        } catch (e: Exception) {
            Log.w(TAG, "deleteIfTempCaptureFile: 清理失败 $path", e)
        }
    }

    /** 清理历史遗留的过期拍照临时文件（相机页初始化时调用一次） */
    suspend fun cleanStaleCaptures(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val dir = java.io.File(context.cacheDir, CAPTURE_DIR_NAME)
                if (!dir.isDirectory) return@withContext
                val cutoff = System.currentTimeMillis() - STALE_THRESHOLD_MS
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) {
                        f.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanStaleCaptures: 清理失败", e)
            }
        }
    }

    // ==================== 保存 ====================

    /**
     * 将 Bitmap 保存到系统图库（Pictures/水印相机/）。
     *
     * - Android 10+：MediaStore RELATIVE_PATH + IS_PENDING，无权限、原子写入
     * - Android 9 及以下：需 WRITE_EXTERNAL_STORAGE（返回 [SaveResult.NeedPermission] 由 UI 请求后重试），
     *   失败时自动降级到应用专属外部存储（免权限，仍可通过"文件"访问）
     *
     * @return [SaveResult.Success] 含最终 Uri；注意成功后调用方才可回收 bitmap
     */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        onProgress: (Boolean) -> Unit = {}
    ): SaveResult = withContext(Dispatchers.IO) {
        onProgress(true)
        try {
            // Android 9- 写公共图库前置权限检查
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) return@withContext SaveResult.NeedPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }

            val name = "${FILE_PREFIX}${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: return@withContext SaveResult.Error("无法创建图库文件")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        throw IOException("JPEG 压缩失败")
                    }
                } ?: throw IOException("无法打开输出流")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null, null
                    )
                }
                Log.i(TAG, "saveToGallery: 已保存 $name")
                SaveResult.Success(uri)
            } catch (e: Exception) {
                // 写入失败清理半成品，避免图库出现损坏占位文件
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                Log.e(TAG, "saveToGallery: 写入失败", e)
                saveToAppPicturesFallback(context, bitmap, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery: 异常", e)
            SaveResult.Error("保存失败：${e.message ?: "未知错误"}")
        } finally {
            onProgress(false)
        }
    }

    /** 公共图库写入失败时的兜底：应用专属外部存储 Pictures 目录（免权限） */
    private fun saveToAppPicturesFallback(
        context: Context,
        bitmap: Bitmap,
        name: String
    ): SaveResult {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: return SaveResult.Error("外部存储不可用")
            val file = java.io.File(dir, name)
            java.io.FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("JPEG 压缩失败")
                }
            }
            // 触发媒体扫描，尝试让部分设备图库发现该文件
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
            )
            Log.w(TAG, "saveToAppPicturesFallback: 已降级保存到 ${file.absolutePath}")
            SaveResult.Error("已保存到应用目录（无法写入系统图库）：$name")
        } catch (e: Exception) {
            Log.e(TAG, "saveToAppPicturesFallback: 失败", e)
            SaveResult.Error("保存失败：${e.message ?: "未知错误"}")
        }
    }
}
