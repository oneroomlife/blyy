package com.azurlane.blyy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import kotlin.math.min
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
 * 裁剪模型（v2，与裁切页预览严格一致）：
 * - 预览：图片以 ContentScale.**Fit** 基准居中放入 3:4 裁剪框（scale=1 时
 *   完整可见整图——立绘多为 2:3~1:2 竖长图，旧 Crop 基准在最小缩放时只露
 *   中段、头脚被裁，表现为"立绘被错误裁切过"），用户在其上叠加
 *   scale（1x~5x）与 offset（绕框中心缩放后平移）。
 * - 裁剪窗口 = 裁剪框反推回原图坐标的矩形（[computeCropWindow]），
 *   **允许越出原图边界**——越出部分在输出中以 [OUTPUT_BANDS_COLOR]
 *   底色补齐（黑边），与预览所见完全一致（所见即所得）。
 * - 输出：按 source→box→canvas 的线性矩阵整体绘制（Canvas.drawBitmap），
 *   不再走"区域解码+坐标钳制"多级换算（旧法钳制与采样换算的舍入会让
 *   选区与实际应用范围漂移）。
 */
object CustomAvatarManager {

    private const val TAG = "CustomAvatarManager"
    private const val ROOT_DIR = "custom_avatars"

    /** 自定义头像输出长边（px）：卡片显示宽度约 120dp（高密度 ~320px），720px 足够且控制文件体积 */
    private const val OUTPUT_MAX_SIDE = 720

    /**
     * 图像未覆盖裁剪框时的补齐底色（深海黑，与 ShipCard 底部渐晕同色族）。
     * 预览框背景与输出画布共用此值——黑边在预览与应用结果中完全一致。
     */
    const val OUTPUT_BANDS_COLOR: Long = 0xFF05101E

    /** 供 Compose 订阅的版本号：apply/remove 后所有 ShipImage 重组并按新缓存键加载 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 裁剪窗口（原图坐标系；left/top 可为负、right/bottom 可越界——越界即黑边） */
    data class CropWindow(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * 计算裁剪窗口（纯函数，JVM 可单测）。
     *
     * 语义与预览层逐像素对应：图片 Fit 居中（baseScale = min 两轴比例），
     * graphicsLayer 绕框中心 scale 缩放后 offset 平移（屏幕像素）。
     *
     * @param srcW/srcH 原图尺寸
     * @param boxW/boxH 裁剪框尺寸（px，须为**实测渲染尺寸**——推导值与真实框
     *   有偏差时 offset 换算进原图坐标会按比例漂移，表现为选区与应用不一致）
     * @param scale 用户缩放（≥1，1 = 整图 Fit 可见）
     * @param offsetX/offsetY 用户平移（预览像素，图片中心相对框中心的偏移）
     */
    fun computeCropWindow(
        srcW: Int,
        srcH: Int,
        boxW: Float,
        boxH: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): CropWindow {
        // Fit 基准缩放：整图可见地放入裁剪框
        val baseScale = min(boxW / srcW, boxH / srcH)
        val actualScale = baseScale * scale

        // 窗口尺寸：裁剪框反推回原图坐标（scale=1 时 ≥ 原图对应维 → 黑边）
        val cropW = boxW / actualScale
        val cropH = boxH / actualScale

        // 窗口中心：图片平移 offset（屏幕px）反推到原图 = 原图中心 − offset/actualScale
        val centerX = srcW / 2f - offsetX / actualScale
        val centerY = srcH / 2f - offsetY / actualScale

        return CropWindow(
            left = centerX - cropW / 2f,
            top = centerY - cropH / 2f,
            width = cropW,
            height = cropH
        )
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
     * 按裁剪参数渲染自定义头像并原子落盘。
     *
     * 渲染 = 预览的离屏重放：source→裁剪框→输出画布 的线性矩阵整体绘制，
     * 黑边用 [OUTPUT_BANDS_COLOR] 补齐——预览与产物严格逐像素一致。
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
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight

            // 采样解码：长边保留 ≥ 2× 输出分辨率即可（绘制裁剪窗口按需放大）
            var sampleSize = 1
            while (maxOf(srcW, srcH) / (sampleSize * 2) >= OUTPUT_MAX_SIDE * 2) sampleSize *= 2
            val source = BitmapFactory.decodeFile(
                srcFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: throw IllegalStateException("图片解码失败: $path")

            // 输出画布：长边 OUTPUT_MAX_SIDE，纵横比与裁剪框一致
            val outH: Int
            val outW: Int
            if (boxH >= boxW) {
                outH = OUTPUT_MAX_SIDE
                outW = (OUTPUT_MAX_SIDE * (boxW / boxH)).roundToInt().coerceAtLeast(1)
            } else {
                outW = OUTPUT_MAX_SIDE
                outH = (OUTPUT_MAX_SIDE * (boxH / boxW)).roundToInt().coerceAtLeast(1)
            }
            // 原图 → 采样图 坐标缩放（src 坐标除以 sample = bitmap 坐标）
            val sample = sampleSize.toFloat()

            // 变换链（每轴独立推导，out = k*s*base*bitmap + k*(c + s*(f0 − c) + t)）：
            //   bitmap →(×base, Fit 居中 f0)→ 裁剪框坐标 →(绕框中心 ×s, +t)→ 层坐标 →(×k)→ 画布
            val baseScale = min(boxW / srcW, boxH / srcH)
            val k = outW / boxW
            val matrix = android.graphics.Matrix().apply {
                val s = scale
                setScale(k * s * baseScale / sample, k * s * baseScale / sample)
                postTranslate(
                    k * (boxW / 2f + s * ((boxW - srcW * baseScale) / 2f - boxW / 2f) + offsetX),
                    k * (boxH / 2f + s * ((boxH - srcH * baseScale) / 2f - boxH / 2f) + offsetY)
                )
            }

            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(output)
            canvas.drawColor(OUTPUT_BANDS_COLOR.toInt())
            val paint = android.graphics.Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
            }
            canvas.drawBitmap(source, matrix, paint)
            source.recycle()

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
            Log.i(TAG, "applyFromCrop: ${outFile.name} ${outW}x${outH} (source ${srcW}x${srcH} sample=$sampleSize, window=${computeCropWindow(srcW, srcH, boxW, boxH, scale, offsetX, offsetY)})")
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
