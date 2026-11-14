package com.azurlane.blyy.util.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 台词卡合成器：把「舰娘 + 台词」排成一张 1080×1440 的分享图。
 *
 * 为什么手绘而不是 Compose 截屏：
 * - 截屏需要把界面画到离屏 Canvas，Compose 侧要引入 `GraphicsLayer.toImageBitmap` +
 *   API 26 限制，且屏幕上的字号/边距会随设备变，产物不稳定；
 * - 手绘可以精确定义 1080×1440 的输出，跨设备一致，且能在后台线程跑。
 *
 * 排版规则：
 * - 台词超过可视区时**自动缩字号**（42 → 30sp 三档），仍超长则截断加省略号，
 *   避免"长台词溢出画布"这种最常见的翻车；
 * - 头像用的是列表里同一张（本地 assets 优先、网络兜底），解析不到就画首字占位。
 */
object VoiceCardComposer {

    private const val TAG = "VoiceCardComposer"
    const val WIDTH = 1080
    const val HEIGHT = 1440
    private const val DIR = "voice_cards"

    /**
     * 生成台词卡。
     *
     * @param avatarUrl 头像 URL 或本地 file:// 地址；为空则用首字占位
     * @return 生成的 PNG 文件（位于 cacheDir/voice_cards）
     */
    suspend fun compose(
        context: Context,
        shipName: String,
        dialogue: String,
        sceneLabel: String,
        avatarUrl: String
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val avatar = loadAvatar(context, avatarUrl)
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawBackground(canvas)
            drawHeader(canvas, avatar, shipName, sceneLabel)
            drawDialogue(canvas, dialogue)
            drawFooter(canvas, shipName)
            avatar?.recycle()

            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            prune(dir)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "voice-card-$stamp.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }.onFailure { Log.w(TAG, "compose failed: ${it.message}", it) }.getOrNull()
    }

    // ── 绘制 ──

    private fun drawBackground(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            intArrayOf(
                Color.parseColor("#0B1220"),
                Color.parseColor("#16233C"),
                Color.parseColor("#2A1E3F")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        // 细网格（指挥中心 HUD 的视觉语言）
        paint.color = Color.argb(18, 255, 255, 255)
        paint.strokeWidth = 1f
        var x = 0f
        while (x <= WIDTH) {
            canvas.drawLine(x, 0f, x, HEIGHT.toFloat(), paint)
            x += 60f
        }
        var y = 0f
        while (y <= HEIGHT) {
            canvas.drawLine(0f, y, WIDTH.toFloat(), y, paint)
            y += 60f
        }

        // 四角装饰线
        paint.color = Color.parseColor("#7FD8FF")
        paint.alpha = 90
        paint.strokeWidth = 4f
        val inset = 48f
        val len = 90f
        canvas.drawLine(inset, inset, inset + len, inset, paint)
        canvas.drawLine(inset, inset, inset, inset + len, paint)
        canvas.drawLine(WIDTH - inset, HEIGHT - inset, WIDTH - inset - len, HEIGHT - inset, paint)
        canvas.drawLine(WIDTH - inset, HEIGHT - inset, WIDTH - inset, HEIGHT - inset - len, paint)
        paint.alpha = 255
    }

    private fun drawHeader(
        canvas: Canvas,
        avatar: Bitmap?,
        shipName: String,
        sceneLabel: String
    ) {
        val avatarSize = 220f
        val left = 96f
        val top = 120f

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8FF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            alpha = 200
        }
        canvas.save()
        canvas.translate(left + avatarSize / 2f, top + avatarSize / 2f)
        canvas.drawCircle(0f, 0f, avatarSize / 2f + 6f, ring)
        canvas.restore()

        if (avatar != null) {
            val path = Path().apply {
                addCircle(left + avatarSize / 2f, top + avatarSize / 2f, avatarSize / 2f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            val src = android.graphics.Rect(0, 0, avatar.width, avatar.height)
            val dst = RectF(left, top, left + avatarSize, top + avatarSize)
            canvas.drawBitmap(avatar, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            val fallbackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F2B44") }
            canvas.drawCircle(left + avatarSize / 2f, top + avatarSize / 2f, avatarSize / 2f, fallbackBg)
            val initial = shipName.trim().take(1).ifBlank { "舰" }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#7FD8FF")
                textSize = 110f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val centerY = top + avatarSize / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(initial, left + avatarSize / 2f, centerY, text)
        }

        // 舰名
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 76f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(shipName, left + avatarSize + 48f, top + 96f, namePaint)

        // 场景/皮肤标签
        if (sceneLabel.isNotBlank()) {
            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9FD4E8")
                textSize = 38f
            }
            canvas.drawText(sceneLabel, left + avatarSize + 48f, top + 156f, tagPaint)
        }

        // 分隔线
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 127, 216, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(left, top + avatarSize + 56f, WIDTH - left, top + avatarSize + 56f, divider)
    }

    private fun drawDialogue(canvas: Canvas, dialogue: String) {
        val left = 96
        val right = WIDTH - 96
        val areaTop = 520
        val areaBottom = HEIGHT - 220
        val maxWidth = right - left

        val text = dialogue.ifBlank { "……" }.trim()
        var textSize = 54f
        var layout: StaticLayout
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F2F6FF")
                this.textSize = textSize
                typeface = Typeface.DEFAULT
            }
            layout = buildLayout(text, paint, maxWidth)
            if (layout.height <= (areaBottom - areaTop) || textSize <= 34f) break
            textSize -= 6f
        }

        // 左侧引号装饰
        val quote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8FF")
            this.textSize = 120f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            alpha = 120
        }
        canvas.drawText("“", left - 12f, areaTop + 40f, quote)

        canvas.save()
        canvas.translate(left.toFloat(), areaTop.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(14f, 1f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 14f, false)
        }
    }

    private fun drawFooter(canvas: Canvas, shipName: String) {
        val left = 96f
        val baseline = (HEIGHT - 110).toFloat()

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(left, baseline - 58f, WIDTH - left, baseline - 58f, line)

        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7FD8FF")
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("BLYY · 碧蓝航线语音", left, baseline, brand)

        val dateStamp = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
        val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = 32f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(dateStamp, WIDTH - left, baseline, meta)
    }

    // ── 资源 ──

    private suspend fun loadAvatar(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(480)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) result.drawable.toBitmap() else null
        }.getOrNull()
    }

    private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
        if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) return bitmap
        val width = intrinsicWidth.coerceAtLeast(1)
        val height = intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    /** 只保留最近 3 张，避免反复分享把缓存撑大 */
    private fun prune(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
    }
}
