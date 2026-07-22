package com.azurlane.blyy.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.io.FileOutputStream

/**
 * App 图标类型 — 仅区分默认与自定义两种状态
 */
enum class AppIconType(val id: String, val displayName: String) {
    DEFAULT("DEFAULT", "默认"),
    CUSTOM("CUSTOM", "自定义快捷方式");

    companion object {
        fun fromId(id: String): AppIconType = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 快捷方式创建/更新结果
 *
 * 用于 UI 层区分不同场景，提供精确的反馈和引导：
 * - [Updated]：快捷方式已创建或更新（首次创建时系统会弹确认框，由 OS 处理）
 * - [NotSupported]：设备/启动器不支持，应引导用户去系统设置或更换启动器
 * - [IconNotFound]：图标文件丢失
 * - [Failed]：其它异常
 */
sealed class PinShortcutResult {
    object Updated : PinShortcutResult()
    object NotSupported : PinShortcutResult()
    object IconNotFound : PinShortcutResult()
    data class Failed(val message: String) : PinShortcutResult()
}

/**
 * App 图标管理器 — 基于 ShortcutManagerCompat 的成熟方案
 *
 * ## 为什么不用 activity-alias？
 *
 * activity-alias 方案存在根本性缺陷：
 * 1. **无法显示用户图片** — alias 的 `android:icon` 只能引用编译期静态 drawable 资源，
 *    用户从相册选择的图片无法在运行时写入 res/ 目录
 * 2. **切换杀进程** — `setComponentEnabledSetting` 会触发系统重建组件，
 *    App 进程被杀死重启，用户体验极差
 * 3. **"正在安装"提示** — Android 8.0+ 每次切换都会显示系统安装动画
 *
 * ## ShortcutManagerCompat 方案优势
 *
 * 1. **支持任意 Bitmap** — 用户图片可直接作为 shortcut 图标显示在桌面
 * 2. **不杀进程** — shortcut 创建/更新不触发组件状态变更
 * 3. **系统确认对话框** — 用户明确知道在添加什么，不会困惑
 * 4. **可静默更新** — 首次创建后，后续更新图标不弹确认框
 * 5. **兼容性** — ShortcutManagerCompat 自动适配 Android 7.0-15+
 *
 * ## 使用流程
 *
 * 1. 用户选择图片 → [generateCustomIcon] 裁剪为 432×432 正方形 Bitmap
 * 2. 调用 [pinCustomShortcut] → 系统弹出"添加到主屏幕"确认（首次）
 * 3. 桌面出现带用户图标的快捷方式，启动器自动裁剪为系统形状
 * 4. 后续更新图标 → 再次调用 [pinCustomShortcut] 静默更新
 * 5. 恢复默认 → [removeCustomShortcut] 禁用快捷方式
 *
 * @param context 应用 Context
 */
class AppIconManager(private val context: Context) {
    companion object {
        private const val TAG = "AppIconManager"

        /** 自定义图标保存目录名（相对 filesDir） */
        private const val ICON_DIR = "icons"

        /** 自定义图标文件名（PNG 格式，保留透明通道） */
        private const val CUSTOM_ICON_FILE = "custom_icon.png"

        /**
         * 自适应图标标准尺寸（像素）
         * Android 自适应图标规范：108dp × 108dp
         * xxxhdpi (4x) 下：108dp = 432px
         */
        private const val ADAPTIVE_ICON_SIZE_PX = 432

        /** Shortcut ID — 固定值，确保只维护一个自定义快捷方式 */
        private const val SHORTCUT_ID = "custom_app_icon"

        /** Shortcut 短标签 — 与应用名一致，无多余后缀 */
        private const val SHORTCUT_SHORT_LABEL = "碧蓝语音"

        /** Shortcut 长标签 — 与短标签一致，避免启动器显示"（自定义图标）"后缀 */
        private const val SHORTCUT_LONG_LABEL = "碧蓝语音"
    }

    private val packageName: String = context.packageName
    private val filesDir: File = context.filesDir
    private val iconDir = File(filesDir, ICON_DIR).apply { if (!exists()) mkdirs() }

    /**
     * 检查当前设备/启动器是否支持固定快捷方式
     *
     * Android 8.0+ 的主流启动器均支持。
     * 低版本或不支持的启动器会返回 false，UI 应据此禁用功能入口。
     */
    fun isPinShortcutSupported(): Boolean {
        return try {
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check pin shortcut support", e)
            false
        }
    }

    /**
     * 从用户选择的图片 URI 生成自定义图标
     *
     * 处理流程：
     * 1. 从 URI 解码原图（采样降采样避免 OOM）
     * 2. 居中裁剪为正方形
     * 3. 缩放到自适应图标标准尺寸（432×432 全幅）
     * 4. 保存为 PNG 到内部存储
     *
     * **为何用全幅图片而不是安全区域 padding？**
     * `IconCompat.createWithAdaptiveBitmap` 将完整 Bitmap 交给启动器，
     * 启动器用自己的形状蒙版（圆形/圆角方形等）裁剪整个 Bitmap。
     * 圆形启动器的蒙版直径 = 整个图标尺寸，因此显示的是完整图片裁剪成圆形。
     * 预览也用 CircleShape 裁剪同一张图片，预览与实际效果一致。
     *
     * 旧版安全区域 padding 方案把图片缩到中心 66.67% 再补白底，
     * 导致预览出现"圆圈中套着方形图片"的丑陋效果。
     *
     * 必须在 IO 线程调用。
     *
     * @param sourceUri 用户选择的图片 URI
     * @return 保存的图标文件绝对路径，失败返回 null
     */
    fun generateCustomIcon(sourceUri: Uri): String? {
        return try {
            // 第一 pass：仅解码尺寸
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                Log.e(TAG, "Cannot decode image bounds from $sourceUri")
                return null
            }

            // 计算采样率
            val sampleSize = calculateSampleSize(
                boundsOptions.outWidth, boundsOptions.outHeight,
                ADAPTIVE_ICON_SIZE_PX, ADAPTIVE_ICON_SIZE_PX
            )

            // 第二 pass：实际解码
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sourceBitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: run {
                Log.e(TAG, "Failed to decode bitmap from $sourceUri")
                return null
            }

            // 居中裁剪为正方形
            val croppedBitmap = centerCropSquare(sourceBitmap)
            // 缩放到目标尺寸（全幅 432×432）
            val scaledBitmap = Bitmap.createScaledBitmap(
                croppedBitmap, ADAPTIVE_ICON_SIZE_PX, ADAPTIVE_ICON_SIZE_PX, true
            )
            // 回收中间 Bitmap — 顺序至关重要：
            // createScaledBitmap 在尺寸已匹配时可能返回与 croppedBitmap 相同的引用，
            // 必须先检查引用相等性再回收，避免回收正在使用的 scaledBitmap
            if (sourceBitmap !== croppedBitmap) sourceBitmap.recycle()
            if (croppedBitmap !== scaledBitmap) croppedBitmap.recycle()

            // 保存为 PNG
            val iconFile = File(iconDir, CUSTOM_ICON_FILE)
            FileOutputStream(iconFile).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            scaledBitmap.recycle()

            Log.i(TAG, "Custom icon generated: ${iconFile.absolutePath} (${ADAPTIVE_ICON_SIZE_PX}x${ADAPTIVE_ICON_SIZE_PX})")
            iconFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate custom icon from $sourceUri", e)
            null
        }
    }

    /**
     * 创建/更新自定义快捷方式
     *
     * - **首次创建**：调用 [ShortcutManagerCompat.requestPinShortcut]，
     *   系统弹出"添加到主屏幕"确认对话框，用户确认后桌面出现快捷方式
     * - **已存在**：调用 [ShortcutManagerCompat.updateShortcuts] 静默更新图标
     * - **已存在但更新失败**（用户已手动删除快捷方式）：降级为 [requestPinShortcut] 重新创建
     *
     * 图标使用用户图片的全幅 Bitmap（432×432），通过 [IconCompat.createWithAdaptiveBitmap]
     * 交给系统裁剪为启动器标准形状。
     *
     * @param iconPath 自定义图标的文件路径（由 [generateCustomIcon] 返回）
     * @return [PinShortcutResult] 区分不同结果，UI 据此提供精确反馈
     */
    fun pinCustomShortcut(iconPath: String): PinShortcutResult {
        if (!isPinShortcutSupported()) {
            Log.w(TAG, "Pin shortcut not supported on this device/launcher")
            return PinShortcutResult.NotSupported
        }

        val iconFile = File(iconPath)
        if (!iconFile.exists()) {
            Log.e(TAG, "Custom icon file not found: $iconPath")
            return PinShortcutResult.IconNotFound
        }

        val iconBitmap = BitmapFactory.decodeFile(iconPath)
        if (iconBitmap == null) {
            Log.e(TAG, "Failed to decode icon bitmap from $iconPath")
            return PinShortcutResult.Failed("图标解码失败")
        }

        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(SHORTCUT_SHORT_LABEL)
            .setLongLabel(SHORTCUT_LONG_LABEL)
            .setIcon(IconCompat.createWithAdaptiveBitmap(iconBitmap))
            .setIntent(
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(packageName, "$packageName.MainActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
            .build()

        val alreadyExists = hasCustomShortcut()

        return try {
            if (alreadyExists) {
                val updated = ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
                if (updated) {
                    Log.i(TAG, "Custom shortcut updated successfully")
                    PinShortcutResult.Updated
                } else {
                    // updateShortcuts 返回 false — 快捷方式可能已被用户手动删除，降级重新创建
                    Log.w(TAG, "updateShortcuts returned false, falling back to requestPinShortcut")
                    val requested = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                    if (requested) PinShortcutResult.Updated
                    else PinShortcutResult.NotSupported
                }
            } else {
                val requested = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                Log.i(TAG, "Pin shortcut requested: $requested")
                if (requested) PinShortcutResult.Updated
                else PinShortcutResult.NotSupported
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pin/update custom shortcut", e)
            PinShortcutResult.Failed(e.message ?: "创建快捷方式失败")
        }
    }

    /**
     * 移除自定义快捷方式
     *
     * 注意：Android 系统不允许应用直接删除用户固定的快捷方式。
     * [ShortcutManagerCompat.disableShortcuts] 会将快捷方式标记为"已禁用"，
     * 图标变灰且点击时显示提示信息。用户需手动长按拖拽删除。
     *
     * @param disabledMessage 用户点击已禁用快捷方式时看到的提示
     * @return true 表示禁用请求已处理
     */
    fun removeCustomShortcut(disabledMessage: String = "自定义快捷方式已移除，请长按删除"): Boolean {
        return try {
            val shortcuts = ShortcutManagerCompat.getShortcuts(
                context, ShortcutManagerCompat.FLAG_MATCH_PINNED
            )
            if (shortcuts.any { it.id == SHORTCUT_ID }) {
                ShortcutManagerCompat.disableShortcuts(
                    context, listOf(SHORTCUT_ID), disabledMessage
                )
                Log.i(TAG, "Custom shortcut disabled")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove custom shortcut", e)
            false
        }
    }

    /**
     * 查询当前是否已存在自定义快捷方式
     */
    fun hasCustomShortcut(): Boolean {
        return try {
            ShortcutManagerCompat.getShortcuts(
                context, ShortcutManagerCompat.FLAG_MATCH_PINNED
            ).any { it.id == SHORTCUT_ID }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打开系统应用详情页，引导用户开启「创建桌面快捷方式」权限
     *
     * 适用场景：
     * - [pinCustomShortcut] 返回 [PinShortcutResult.NotSupported] 时
     * - 部分国产 ROM（MIUI/ColorOS/OriginOS）限制后台创建快捷方式，
     *   用户需在系统设置中手动授权
     *
     * Android 标准快捷方式 API 无运行时权限申请流程，
     * 只能引导用户到应用详情页自行开启。
     */
    fun openAppShortcutSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }

    /**
     * 获取自定义图标文件路径（若存在）
     */
    fun getCustomIconPath(): String? {
        val iconFile = File(iconDir, CUSTOM_ICON_FILE)
        return if (iconFile.exists()) iconFile.absolutePath else null
    }

    /**
     * 删除自定义图标文件
     */
    fun clearCustomIcon() {
        val iconFile = File(iconDir, CUSTOM_ICON_FILE)
        if (iconFile.exists()) {
            iconFile.delete()
            Log.d(TAG, "Custom icon file cleared")
        }
    }

    // ── 内部工具方法 ──

    /** 计算 BitmapFactory 采样率 */
    private fun calculateSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) {
            sample *= 2
        }
        return sample
    }

    /** 居中裁剪为正方形 */
    private fun centerCropSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }
}
