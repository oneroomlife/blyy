package com.azurlane.blyy.util.share

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /** 台词区最小字号：再小在 1080 宽下已经不好读，此时改为省略号截断 */
    private const val MIN_DIALOGUE_TEXT = 34f

    /** 台词字号递减步长 */
    private const val TEXT_STEP = 6f

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
        var card: Bitmap? = null
        try {
            val avatar = VoiceCardAssets.loadAvatar(context, avatarUrl, 480)
            // loadAvatar 内部的 runCatching 会吞掉外层取消信号，这里补检：
            // 用户已离开/再次取消时不再白画一张 6 MB 的图
            currentCoroutineContext().ensureActive()
            card = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(card)
            drawBackground(canvas)
            drawHeader(canvas, avatar, shipName, sceneLabel)
            drawDialogue(canvas, dialogue)
            drawFooter(canvas, shipName)

            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "voice-card-$stamp.png")
            file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 落盘后再 prune：先删旧文件会在"刚写完就轮到自己被删"的边界上发出空文件
            prune(dir)
            file
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 结构化并发：取消必须向上传播，不能被当成"合成失败"吞掉
            throw c
        } catch (oom: OutOfMemoryError) {
            // 1080×1440 ARGB_8888 约 6 MB，低端机上叠加立绘解码可能 OOM：
            // 分享是锦上添花，宁可返回 null 提示重试，也不要让进程被系统杀掉
            Log.w(TAG, "compose OOM, abort", oom)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "compose failed: ${t.message}", t)
            null
        } finally {
            // 只回收自己创建的卡片位图。
            // ⚠️ 绝不能 recycle avatar：loadAvatar 返回的是 Coil 内存缓存里的**共享实例**
            // （SuccessResult.drawable 是包着缓存位图的 BitmapDrawable，toBitmap 不做拷贝），
            // 一旦 recycle 就把缓存条目毒化——之后每次生成都会拿到"已回收的位图"，
            // drawBitmap 抛 IllegalStateException → compose 返回 null →「台词卡生成失败」。
            runCatching { card?.recycle() }
        }
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
            // 按码点取首字：take(1) 会把 emoji 舰名切成孤立代理项，画出来是个方框
            val initial = VoiceCardPolicy.takeCodePoints(shipName.trim(), 1).ifBlank { "舰" }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#7FD8FF")
                textSize = 110f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val centerY = top + avatarSize / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(initial, left + avatarSize / 2f, centerY, text)
        }

        // 舰名：76px + 自适应缩字号 + 省略号。
        // 原来直接 drawText，遇到"提康德罗加·META"这类长名（或学生名带后缀）会一路画到画布
        // 右边界之外被裁掉，卡片上只剩半截名字——比缩字号难看得多。
        val nameLeft = left + avatarSize + 48f
        val nameMaxWidth = WIDTH - left - nameLeft
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 76f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val safeName = shipName.trim().ifBlank { "未知舰船" }
        fitTextSize(namePaint, safeName, nameMaxWidth, minSize = 40f)
        canvas.drawText(
            ellipsize(namePaint, safeName, nameMaxWidth),
            nameLeft,
            top + 96f,
            namePaint
        )

        // 场景/皮肤标签：同样限宽，论文档里最长有「好感度-爱·誓约」这种
        if (sceneLabel.isNotBlank()) {
            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9FD4E8")
                textSize = 38f
            }
            canvas.drawText(
                ellipsize(tagPaint, sceneLabel, nameMaxWidth),
                nameLeft,
                top + 156f,
                tagPaint
            )
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
        val maxHeight = areaBottom - areaTop

        val text = dialogue.ifBlank { "……" }.trim().replace("\r\n", "\n").replace('\r', '\n')
        var textSize = 54f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2F6FF")
            typeface = Typeface.DEFAULT
            this.textSize = textSize
        }
        var layout = buildLayout(text, paint, maxWidth)
        // 条件写成 textSize - STEP >= MIN：旧写法 textSize > MIN 会一路减到 30（低于下限），
        // 长台词被缩成蚂蚁字反而不如截断
        while (layout.height > maxHeight && textSize - TEXT_STEP >= MIN_DIALOGUE_TEXT) {
            textSize -= TEXT_STEP
            paint.textSize = textSize
            layout = buildLayout(text, paint, maxWidth)
        }
        // 缩到最小字号仍放不下：截断并补省略号。
        // 旧实现只靠 clipRect 硬裁，最后一行会被切掉半截字（读者看到的是断句），
        // 而"…"能明确告诉他这句还没说完。
        if (layout.height > maxHeight) {
            layout = ellipsizeLayout(text, paint, maxWidth, maxHeight)
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
        // 裁剪到台词区：兜底防止任何残留溢出画进 footer 覆盖落款
        canvas.clipRect(left.toFloat(), areaTop.toFloat(), right.toFloat(), areaBottom.toFloat())
        canvas.translate(left.toFloat(), areaTop.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    /** 二分找到能塞进 [maxHeight] 的最长前缀，再拼一个省略号 */
    private fun ellipsizeLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxHeight: Int
    ): StaticLayout {
        val ellipsis = "…"
        var low = 0
        var high = text.length
        var best = ellipsis
        while (low <= high) {
            val mid = (low + high) / 2
            // 对齐到码点边界，避免切出孤立代理项
            val end = if (mid in 1 until text.length &&
                Character.isLowSurrogate(text[mid]) &&
                Character.isHighSurrogate(text[mid - 1])
            ) mid - 1 else mid
            val candidate = text.substring(0, end).trimEnd() + ellipsis
            if (buildLayout(candidate, paint, width).height <= maxHeight) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return buildLayout(best, paint, width)
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
        // 舰名 + 日期：卡片被转发出去后会脱离 App 上下文，
        // 旧版只有品牌与日期，收图的人无从知道这是哪艘船（舰名只在上半部分，
        // 而聊天软件常把方图裁成正方形把上半截截掉）
        val nameTag = shipName.trim()
        val metaText = if (nameTag.isEmpty()) dateStamp else "${VoiceCardPolicy.takeCodePoints(nameTag, 16)} · $dateStamp"
        canvas.drawText(ellipsize(meta, metaText, WIDTH - left - left), WIDTH - left, baseline, meta)
    }

    // ── 文本排版工具 ──

    /**
     * 逐级缩字号直到文本塞进 [maxWidth]；到 [minSize] 仍塞不下就交给 [ellipsize]。
     *
     * 直接用 `drawText` 是台词卡最常见的翻车点：文案长度不可控（舰名/场景来自 wiki），
     * 而画布宽度固定 1080。
     */
    private fun fitTextSize(paint: Paint, text: String, maxWidth: Float, minSize: Float) {
        if (text.isEmpty()) return
        while (paint.textSize > minSize && paint.measureText(text) > maxWidth) {
            paint.textSize -= 4f
        }
    }

    /** 用省略号收尾（按码点回退，绝不切出孤立代理项） */
    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end)
            if (paint.measureText(candidate) + ellipsisWidth <= maxWidth) return candidate + ellipsis
            // 回退一个码点，而不是一个 char：否则可能留下半个 emoji（渲染成方框）
            end -= if (end >= 2 && Character.isLowSurrogate(text[end - 1]) &&
                Character.isHighSurrogate(text[end - 2])
            ) 2 else 1
        }
        return ellipsis
    }

    // ── 资源 ──

    /** 只保留最近 3 张，避免反复分享把缓存撑大 */
    private fun prune(dir: File) = VoiceCardAssets.prune(dir, keep = 3)
}
