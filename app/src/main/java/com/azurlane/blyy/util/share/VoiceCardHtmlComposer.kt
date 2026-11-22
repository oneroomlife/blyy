package com.azurlane.blyy.util.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * HTML 台词语音卡合成器。
 *
 * 产物是**一个轻量 HTML 文件**：头像内嵌（本地 assets 图，任何环境都能显示），
 * 语音用**原站链接**（卡片只有几十 KB，转发不耗流量），页面自带播放器
 * （波形 / 进度条 / 拖动定位）用原生 `<audio>` + 一点细 JS 实现。
 *
 * 为什么语音不再内嵌 base64：
 * - 一条台词 300 KB ~ 1.5 MB，base64 后膨胀 4/3，卡片动辄 2 MB，聊天里转发很重；
 * - 分享出去的是"链接"，接收方每次播放都取最新音频，不会因为临时缓存被清理而失效。
 * 代价是播放需要联网（原站 CDN 可能还要求 Referer），页面里已做"加载失败可重试"的降级。
 *
 * 模板放在 `assets/share/voice_card.html`（可单独预览/改版式），这里只做占位符替换，
 * 因此 [render] 是纯函数，能直接跑 JVM 单测（见 `VoiceCardHtmlRenderTest`）。
 *
 * ⚠️ 占位符替换是**单趟扫描**（[fillTemplate]），不是链式 `String.replace`：
 * 链式替换时先填入的用户数据（台词/舰名）会被后续的 `replace("{{BARS}}", …)` 再扫一遍，
 * 一句写着 `{{BARS}}` 的台词就能把 40 根波形柱塞进正文里——这是二次注入，不是理论风险。
 */
object VoiceCardHtmlComposer {

    private const val TAG = "VoiceCardHtmlComposer"

    /** 卡片落地目录（FileProvider 已声明 voice_cards_html/） */
    private const val DIR = "voice_cards_html"

    private const val TEMPLATE_PATH = "share/voice_card.html"

    /** 头像最多 512 px：卡片上最大只显示 80 dp，再大纯属浪费体积 */
    private const val AVATAR_MAX_PX = 512
    private const val AVATAR_QUALITY = 88

    /** 播放器波形柱数量，与模板 CSS 的 flex 布局一致 */
    private const val BAR_COUNT = 40

    /** 生成后缓存里保留的卡片数（含刚生成的那张，留给预览浮层读文件） */
    private const val KEEP_CARDS = 3

    /**
     * 模板只读一次。
     *
     * 模板是编译进 APK 的 assets（几十 KB），但 `assets.open()` 每次都要过 AssetManager
     * 与解压；连点几次分享就白读几次，没有必要。字符串不可变，缓存是安全的。
     */
    @Volatile
    private var cachedTemplate: String? = null

    /** 合成结果：没有可用语音地址时 [hasAudio] = false，播放器渲染为禁用态 */
    data class Output(val file: File, val hasAudio: Boolean)

    /** 模板替换所需的全部字段（不含 Android 依赖，便于单测） */
    data class Content(
        val shipName: String,
        val sceneLabel: String,
        val dialogue: String,
        val serial: String,
        val dateLabel: String,
        val avatarDataUri: String?,
        val audioUrl: String
    )

    /**
     * 生成一张 HTML 台词语音卡。
     *
     * @param avatarUrl 头像地址（本地 assets / 自定义头像 / 网络兜底）；拿不到就用舰名首字纹章
     * @param audioUrl  当前语言分支的音频原站地址；空/非法 → 卡片仍生成，播放器降级
     * @return 生成的文件（位于 cacheDir/voice_cards_html），失败返回 null
     */
    suspend fun compose(
        context: Context,
        shipName: String,
        sceneLabel: String,
        dialogue: String,
        serial: String,
        avatarUrl: String,
        audioUrl: String
    ): Output? = withContext(Dispatchers.IO) {
        try {
            val template = loadTemplate(context)
            val avatarDataUri = embedAvatar(context, avatarUrl)
            // 非法 scheme（相对路径 / content: / javascript:）一律按"无语音"处理：
            // 否则卡片里会出现一个点了永远没反应的播放按钮
            val playableUrl = audioUrl.trim().takeIf { VoiceCardPolicy.isHttpUrl(it) }.orEmpty()

            val html = render(
                template = template,
                content = Content(
                    shipName = shipName,
                    sceneLabel = sceneLabel,
                    dialogue = dialogue,
                    serial = serial,
                    dateLabel = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()),
                    avatarDataUri = avatarDataUri,
                    audioUrl = playableUrl
                )
            )

            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            // 带毫秒：同一秒内连点两次不会互相覆盖（图片版沿用秒级命名，够用）
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = File(dir, "voice-card-$stamp.html")
            file.writeText(html, Charsets.UTF_8)
            // 写完再 prune：先 prune 会让"刚生成的这张"在第 N 次分享时被自己挤掉的旧文件顶上
            VoiceCardAssets.prune(dir, keep = KEEP_CARDS)
            Output(file, hasAudio = playableUrl.isNotEmpty())
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 结构化并发：取消必须向上传播，不能被当成「合成失败」
            throw c
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "compose OOM, abort", oom)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "compose failed: ${t.message}", t)
            null
        }
    }

    // ── 模板渲染（纯函数） ──

    internal fun render(template: String, content: Content): String {
        val hasAudio = VoiceCardPolicy.isHttpUrl(content.audioUrl)
        val avatarBlock = content.avatarDataUri?.takeIf { it.isNotBlank() }
            ?.let { """<img src="$it" alt="" />""" }
            ?: """<span class="mono">${escapeHtml(monogram(content.shipName))}</span>"""

        // 只保留场景标签：语言（中配/日配）不属于"这张卡"的身份信息
        val tags = if (content.sceneLabel.isNotBlank()) {
            """<span class="tag">${escapeHtml(content.sceneLabel)}</span>"""
        } else {
            ""
        }

        // 台词里的换行是作者分句，转成 <br> 才不会被折行规则吃掉。
        // \r 先归一：从网页抓来的台词偶尔带 CRLF，留着会在卡片上多出一个空行。
        val dialogueHtml = escapeHtml(content.dialogue.replace("\r\n", "\n").replace('\r', '\n').ifBlank { "……" })
            .replace("\n", "<br />")

        return fillTemplate(
            template,
            mapOf(
                "TITLE" to escapeHtml("${content.shipName} · 台词语音卡"),
                "SHIP" to escapeHtml(content.shipName.ifBlank { "未知舰船" }),
                "SERIAL" to escapeHtml(content.serial.ifBlank { "VOICE ARCHIVE" }),
                "TAGS" to tags,
                "AVATAR" to avatarBlock,
                "QUOTE_CLASS" to quoteClassFor(content.dialogue),
                "DIALOGUE" to dialogueHtml,
                "BARS" to waveBars(BAR_COUNT, seedOf(content.dialogue)),
                "HINT" to if (hasAudio) "轻触播放 · 语音取自原站，需联网" else "该台词暂无语音 · 可截图保存卡片",
                "PLAYER_CLASS" to if (hasAudio) "" else " is-dead",
                "PLAY_BTN_DISABLED" to if (hasAudio) "" else " disabled",
                "AUDIO_ATTR" to if (hasAudio) " src=\"" + escapeHtml(content.audioUrl) + "\"" else "",
                "DATE" to escapeHtml(content.dateLabel)
            )
        )
    }

    /**
     * 单趟占位符替换。
     *
     * 关键性质：**填入的值不再参与扫描**。链式 `replace` 做不到这一点——
     * 台词里只要出现 `{{BARS}}`，就会被后面的替换展开成一堆 `<span>`。
     * 未知占位符原样保留（而非替换成空串），这样模板写错字时单测能立刻抓到。
     */
    private fun fillTemplate(template: String, values: Map<String, String>): String {
        val out = StringBuilder(template.length + 4096)
        var cursor = 0
        while (cursor < template.length) {
            val start = template.indexOf("{{", cursor)
            if (start < 0) {
                out.append(template, cursor, template.length)
                break
            }
            val end = template.indexOf("}}", start + 2)
            if (end < 0) {
                out.append(template, cursor, template.length)
                break
            }
            val key = template.substring(start + 2, end)
            val value = values[key]
            if (value == null) {
                // 原样保留：{{UNKNOWN}} 留给单测/肉眼发现
                out.append(template, cursor, end + 2)
            } else {
                out.append(template, cursor, start)
                out.append(value)
            }
            cursor = end + 2
        }
        return out.toString()
    }

    /** 台词越长字号越小；阈值按「中文一行 ~14 字」的实测版式定的（按码点，emoji 不占两格） */
    internal fun quoteClassFor(dialogue: String): String {
        val length = if (dialogue.isEmpty()) 0 else dialogue.codePointCount(0, dialogue.length)
        return when {
            length <= 34 -> "q-xl"
            length <= 70 -> "q-l"
            length <= 120 -> "q-m"
            else -> "q-s"
        }
    }

    /**
     * 生成播放器波形。
     *
     * 用「语音包络」近似：中段振幅大、两端收窄，再叠一段确定性随机，
     * 这样每张卡的波形都不一样，但同一句台词每次生成都一致（截图/分享可复现）。
     */
    internal fun waveBars(count: Int, seed: Long): String {
        val random = Random(seed)
        val builder = StringBuilder(count * 62)
        var prev = 0.55f
        for (i in 0 until count) {
            val envelope = 0.45f + 0.55f * sin(PI * i / count).toFloat()
            val target = (0.16f + random.nextFloat() * 0.84f) * envelope
            prev = prev * 0.45f + target * 0.55f
            val height = String.format(Locale.US, "%.2f", prev.coerceIn(0.12f, 1f))
            builder.append("""<span class="bar" style="--h:""")
                .append(height)
                .append(";--d:")
                .append(i * 40)
                .append("ms\"></span>")
        }
        return builder.toString()
    }

    internal fun escapeHtml(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun monogram(shipName: String): String =
        shipName.trim().let { name ->
            // 按码点取首字：直接 take(1) 会把 emoji 舰名切成孤立代理项
            if (name.isEmpty()) "舰" else VoiceCardPolicy.takeCodePoints(name, 1)
        }

    private fun seedOf(dialogue: String): Long = dialogue.hashCode().toLong()

    // ── 素材 ──

    private fun loadTemplate(context: Context): String {
        cachedTemplate?.let { return it }
        val loaded = context.assets.open(TEMPLATE_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        cachedTemplate = loaded
        return loaded
    }

    /** 头像压成 512px JPEG（透明底填深蓝），base64 后约 40~120 KB */
    private suspend fun embedAvatar(context: Context, avatarUrl: String): String? {
        // Coil 的 suspend 取图；返回的是共享缓存位图，只读不动它
        val source = VoiceCardAssets.loadAvatar(context, avatarUrl, AVATAR_MAX_PX) ?: return null
        return runCatching {
            val bytes = flattenToJpeg(source, AVATAR_MAX_PX, AVATAR_QUALITY)
            if (bytes.isEmpty()) null
            else "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * 不 recycle 源位图（Coil 共享缓存）；只在自建的副本上回收。
     *
     * 缩略仍走 createScaledBitmap 之外的路径（Canvas 绘制）：需要先把透明底压成实色，
     * 顺手在这一步完成缩放，省一次全尺寸中间位图。
     */
    private fun flattenToJpeg(source: Bitmap, maxPx: Int, quality: Int): ByteArray {
        if (source.width <= 0 || source.height <= 0) return ByteArray(0)
        val scale = min(1f, maxPx.toFloat() / maxOf(source.width, source.height))
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        var target: Bitmap? = null
        try {
            target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            // 透明头像直接压 JPEG 会变黑块，先铺一层与卡片同色系的底
            canvas.drawColor(Color.parseColor("#0E1E33"))
            canvas.drawBitmap(
                source,
                null,
                RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            val out = ByteArrayOutputStream(targetWidth * targetHeight / 4)
            if (!target.compress(Bitmap.CompressFormat.JPEG, quality, out)) return ByteArray(0)
            return out.toByteArray()
        } finally {
            runCatching { target?.recycle() }
        }
    }
}
